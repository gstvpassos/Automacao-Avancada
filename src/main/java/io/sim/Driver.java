package io.sim;

import de.tudresden.sumo.cmd.Route;
import de.tudresden.sumo.cmd.Vehicle;
import de.tudresden.sumo.objects.SumoStringList;
import it.polito.appeal.traci.SumoTraciConnection;
import sim.traci4j.src.java.it.polito.appeal.traci.protocol.Constants; // Para VAR_ROUTE_INDEX

import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.security.PublicKey;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantLock; // Se ainda for usar para algo não SUMO
import java.util.logging.Level;
import java.util.logging.Logger;

import org.json.JSONObject; // Se for usar para parsear resposta do AlphaBank

import io.sim.utils.JsonUtil; // Se for usar para parsear resposta do AlphaBank

/**
 * Classe que representa um motorista no sistema de simulação.
 * Implementa Thread para execução concorrente.
 * Gerencia um carro, executa rotas sequencialmente no SUMO e atua como cliente para o AlphaBank.
 */
public class Driver extends Thread {

    private static final Logger logger = Logger.getLogger(Driver.class.getName());

    // Identificação
    private final String driverId;
    private final String nome;
    private Car car;

    // Conexão AlphaBank
    private Socket bankConnection;
    private ObjectOutputStream outdriverStream; 
    private ObjectInputStream indrivStream;  
    private Account account;
    private boolean connectedToBank; // Renomeado de 'connected'
    private EncriptaDecriptaDES alphaBankSessionEncryptor; // Renomeado de 'sessionEncryptor'

    // Rotas
    private final ArrayList<Rota> rotasAExecutarOriginais; // Lista original de rotas
    private final ArrayList<Rota> rotasExecutadas;

    // Locks (avaliar se ainda são necessários com a nova lógica de run())
    private final ReentrantLock lockRotasAExecutar = new ReentrantLock();
    private final ReentrantLock lockRotasExecutadas = new ReentrantLock();

    private BotPayment botPayment;
    private AtomicBoolean running = new AtomicBoolean(false);
    private final double FUEL_PRICE_PER_KM = 5.87; // Exemplo, pode ser do Carro

    // Conexão SUMO e controle de retry para inicialização de rotas
    private SumoTraciConnection sumo;
    private int maxRetriesRouteInit = 5;
    private int initialRetryDelayRouteInitMs = 500;

    private EnvSimulator envSimulator; // Para interagir com o monitor de threads do EnvSimulator

    public Driver(String driverId, String nome, Car car,
                  String bankHost, int bankPort,
                  String login, String password,
                  double initialBalance,
                  SumoTraciConnection sumo, // ESSENCIAL: Passar a conexão SUMO
                  EnvSimulator envSimulator) { // Para monitoramento de threads
        super(driverId); // Nome da Thread
        this.driverId = driverId;
        this.setName(driverId); // Define o nome da thread
        this.nome = nome;
        this.car = car;
        this.sumo = sumo; // Armazena a conexão SUMO
        this.envSimulator = envSimulator; // Armazena referência ao EnvSimulator

        this.rotasAExecutarOriginais = new ArrayList<>();
        this.rotasExecutadas = new ArrayList<>();
        this.running.set(false); // Será definido como true no início do run()
        this.connectedToBank = false;

        // Cria conta no AlphaBank
        this.account = new Account(login, password, initialBalance);

        // Conecta ao AlphaBank (a negociação de chave ocorre aqui)
        try {
            connectToAlphaBank(bankHost, bankPort); // Método renomeado para clareza
        } catch (IOException e) {
            logger.log(Level.WARNING, "Driver " + driverId + " falhou ao conectar com AlphaBank na inicialização: " + e.getMessage() + ". Tentará operar sem AlphaBank.");
            // O Driver pode continuar sem o banco, mas funcionalidades de pagamento falharão.
            // 'connectedToBank' permanecerá false.
        } catch (Exception e) {
             logger.log(Level.SEVERE, "Driver " + driverId + " erro não esperado ao conectar com AlphaBank na inicialização: " + e.getMessage(), e);
        }


        // Inicializa o bot de pagamento (PRECISA da conta e da conexão SEGURA com o banco)
        // É importante que o BotPayment também use a chave de sessão negociada se for se comunicar
        // através da mesma conexão, ou que tenha sua própria negociação.
        // A implementação atual do BotPayment o faz conectar-se independentemente.
        if (this.account != null) { // Só cria bot se a conta foi criada
             this.botPayment = new BotPayment(this.account, bankHost, bankPort); // BotPayment lida com sua própria conexão/autenticação
        } else {
            logger.warning("Driver " + driverId + ": Conta AlphaBank não criada, BotPayment não será inicializado.");
        }


        logger.info("Driver " + driverId + " criado.");
    }

    // Método para adicionar rotas ao Driver pelo EnvSimulator
    public void addRota(Rota r) {
        lockRotasAExecutar.lock();
        try {
            this.rotasAExecutarOriginais.add(r);
        } finally {
            lockRotasAExecutar.unlock();
        }
    }
    
    public List<Rota> getRotasAExecutar() {
        lockRotasAExecutar.lock();
        try {
            // Retorna uma cópia para evitar ConcurrentModificationException se a lista original for modificada
            return new ArrayList<>(this.rotasAExecutarOriginais);
        } finally {
            lockRotasAExecutar.unlock();
        }
    }


    private void connectToAlphaBank(String host, int bankPort) throws IOException, ClassNotFoundException, Exception {
        logger.info("Driver " + driverId + " iniciando conexão com AlphaBank em " + host + ":" + bankPort);
        this.bankConnection = new Socket();
        this.bankConnection.connect(new InetSocketAddress(host, bankPort), 10000); // 10s timeout

        this.outdriverStream = new ObjectOutputStream(bankConnection.getOutputStream());
        this.outdriverStream.flush();
        this.indrivStream = new ObjectInputStream(bankConnection.getInputStream());
        logger.info("Driver " + driverId + ": Streams AlphaBank criadas.");

        String clientIdForBank = this.driverId + "_bank_" + UUID.randomUUID().toString();
        this.outdriverStream.writeObject(clientIdForBank);
        this.outdriverStream.flush();

        String serverRsaPublicKeyBase64 = (String) this.indrivStream.readObject();
        PublicKey serverRsaPublicKey = EncriptaDecriptaRSA.getPublicKeyFromBase64(serverRsaPublicKeyBase64);

        EncriptaDecriptaDES desKeyGenerator = new EncriptaDecriptaDES();
        byte[] desSessionKeyBytes = desKeyGenerator.getChaveDESBytes();
        this.alphaBankSessionEncryptor = new EncriptaDecriptaDES(desSessionKeyBytes);

        byte[] encryptedDesSessionKey = EncriptaDecriptaRSA.criptografarComPublicKey(desSessionKeyBytes, serverRsaPublicKey);
        this.outdriverStream.writeObject(encryptedDesSessionKey);
        this.outdriverStream.flush();

        String encryptedConfirmation = (String) this.indrivStream.readObject();
        String confirmationMessage = this.alphaBankSessionEncryptor.descriptografar(encryptedConfirmation);

        if (!"CONNECTED_SECURELY".equals(confirmationMessage)) {
            throw new IOException("Falha ao estabelecer conexão segura com AlphaBank. Confirmação inválida: " + confirmationMessage);
        }
        this.connectedToBank = true;
        logger.info("Driver " + driverId + " conectado de forma SEGURA ao AlphaBank.");
        sendAuthenticationRequestToAlphaBank();
    }

    private void sendAuthenticationRequestToAlphaBank() throws IOException, Exception {
        if (!this.connectedToBank || this.alphaBankSessionEncryptor == null) {
            throw new IOException("Não conectado de forma segura ao AlphaBank para autenticação.");
        }
        Map<String, Object> authData = new HashMap<>();
        authData.put("action", "AUTHENTICATE");
        authData.put("login", account.getLogin());
        authData.put("password", account.getSenha());
        authData.put("clientType", "DRIVER");
        String authRequest = JsonUtil.toJson(authData);

        String encryptedRequest = this.alphaBankSessionEncryptor.criptografar(authRequest);
        this.outdriverStream.writeObject(encryptedRequest);
        this.outdriverStream.flush();

        String encryptedResponse = (String) this.indrivStream.readObject();
        String response = this.alphaBankSessionEncryptor.descriptografar(encryptedResponse);
        
        Map<String, Object> responseMap = JsonUtil.jsonToMap(response);
        if (responseMap == null || !"success".equals(responseMap.get("status"))) {
            throw new IOException("Falha na autenticação com AlphaBank: " + response);
        }
        logger.info("Driver " + driverId + " autenticado com sucesso no AlphaBank.");
    }


    @Override
    public void run() {
        if (!this.connectedToBank && account == null) {
             logger.severe("Driver " + driverId + " não pode iniciar. Falha na conexão/autenticação inicial com o banco ou conta não criada.");
             if (envSimulator != null) envSimulator.markThreadTerminated(this.driverId);
             return;
        }
        this.running.set(true);

        // Inicia o BotPayment SE a conexão com o banco foi bem sucedida E o bot foi criado
        if (this.botPayment != null && this.connectedToBank) {
            this.botPayment.start();
        } else if (this.botPayment == null) {
             logger.warning("Driver " + driverId + ": BotPayment não inicializado.");
        } else {
            logger.warning("Driver " + driverId + ": Não conectado ao banco, BotPayment não será iniciado.");
        }

        logger.info("Driver " + this.driverId + " (Car: " + this.car.getIdCar() + ") processando rotas sequencialmente.");
        
        List<Rota> rotasParaProcessar = getRotasAExecutar(); // Pega a lista de rotas
        boolean isFirstRouteForThisCar = true;

        for (Rota rotaAtual : rotasParaProcessar) {
            if (!this.running.get()) {
                logger.info("Driver " + this.driverId + " interrompido antes da rota " + rotaAtual.getIdRota());
                break;
            }

            if (envSimulator != null) envSimulator.updateThreadActivity(this.driverId);
            logger.info("Driver " + this.driverId + " iniciando processamento da rota " + rotaAtual.getIdRota());

            boolean routeInitializedSuccessfully = false;
            int attempts = 0;
            int currentRetryDelay = initialRetryDelayRouteInitMs;

            while (attempts < maxRetriesRouteInit && !routeInitializedSuccessfully && this.running.get()) {
                try {
                    if (attempts > 0) {
                        logger.info("Driver " + this.driverId + " (Rota: " + rotaAtual.getIdRota() + ") tentando inicialização novamente (tentativa " + (attempts + 1) + "/" + maxRetriesRouteInit + ")");
                        Thread.sleep(currentRetryDelay);
                        currentRetryDelay *= 2;
                    }

                    if (this.sumo == null || this.sumo.isClosed()) {
                        throw new Exception("Conexão SUMO está fechada ou nula para Driver " + driverId);
                    }

                    String vehicleID = this.car.getIdCar();
                    String routeID = rotaAtual.getIdRota();
                    SumoStringList edgeList = new SumoStringList();
                    String[] arestasDaRota = rotaAtual.getRota();

                    if (arestasDaRota == null || arestasDaRota.length == 0) {
                        throw new Exception("Rota " + routeID + " para Driver " + driverId + " não contém arestas.");
                    }
                    for (String edge : arestasDaRota) {
                        edgeList.add(edge);
                    }
                    
                    // Sincronizar no objeto sumo pode ser uma boa ideia se ele é compartilhado entre threads
                    // e as operações do Traci4J não são totalmente thread-safe para sequências de comandos.
                    // A biblioteca Traci4J sincroniza 'exchangeQuery', que é usado por do_job_set/get.
                    // Vamos assumir por agora que é suficiente.
                    
                    logger.info("Driver " + this.driverId + " (Car: " + vehicleID + ") definindo/adicionando rota SUMO: " + routeID);
                    this.sumo.do_job_set(Route.add(routeID, edgeList));

                    if (isFirstRouteForThisCar) {
                        // Verifica se o veículo já existe (pode ter sido adicionado por outro meio ou em uma execução anterior não limpa)
                        SumoStringList existingVehicles = (SumoStringList) this.sumo.do_job_get(Vehicle.getIDList());
                        if (existingVehicles.contains(vehicleID)) {
                            logger.warning("Driver " + this.driverId + ": Veículo " + vehicleID + " já existe no SUMO, mas esta é marcada como a primeira rota. Tentando definir rota.");
                            this.sumo.do_job_set(Vehicle.setRouteID(vehicleID, routeID));
                        } else {
                            logger.info("Driver " + this.driverId + " (primeira rota) adicionando veículo: " + vehicleID + " à rota " + routeID);
                            this.sumo.do_job_set(Vehicle.addFull(vehicleID, routeID, "DEFAULT_VEHTYPE", "now", // ou tipo do carro
                                                        "0", "0", "0", "current", "max", "current",
                                                        "", "", "", this.car.getPersonCapacity(), this.car.getPersonNumber()));
                        }
                    } else {
                        logger.info("Driver " + this.driverId + " (rota subsequente) atribuindo rota " + routeID + " ao veículo existente " + vehicleID);
                        this.sumo.do_job_set(Vehicle.setRouteID(vehicleID, routeID));
                        // Para garantir que o veículo reinicie corretamente na nova rota:
                        this.sumo.do_job_set(Vehicle.setSpeed(vehicleID, -1)); 
                        // this.sumo.do_job_set(Vehicle.resume(vehicleID)); // Se o veículo puder estar "parado" (não apenas fim da rota)
                    }
                    this.sumo.do_job_set(Vehicle.setColor(vehicleID, this.car.getColorCar()));
                    logger.info("Driver " + this.driverId + " (Car: " + vehicleID + ") configurado para rota " + routeID);
                    routeInitializedSuccessfully = true;

                } catch (InterruptedException e) {
                    logger.warning("Driver " + this.driverId + " interrompido durante inicialização da rota " + rotaAtual.getIdRota());
                    Thread.currentThread().interrupt();
                    this.running.set(false); 
                    break; 
                } catch (Exception e) {
                    attempts++;
                    logger.log(Level.WARNING, "Driver " + this.driverId + " (Rota: " + rotaAtual.getIdRota() + ") falha na inicialização (tentativa " + attempts + "): " + e.getMessage(), e);
                    if (this.sumo != null && this.sumo.isClosed()) {
                        logger.severe("Driver " + this.driverId + " (Rota: " + rotaAtual.getIdRota() + ") conexão SUMO está fechada. Abortando tentativas.");
                        this.running.set(false);
                        break; 
                    }
                    if (attempts >= maxRetriesRouteInit) {
                         logger.severe("Driver " + this.driverId + " (Rota: " + rotaAtual.getIdRota() + ") falhou ao inicializar após " + maxRetriesRouteInit + " tentativas.");
                         this.running.set(false); 
                    }
                }
            }

            if (!routeInitializedSuccessfully || !this.running.get()) {
                if (this.running.get()) { 
                     logger.severe("Driver " + this.driverId + " não conseguiu inicializar a rota " + rotaAtual.getIdRota() + ". Parando driver.");
                     this.running.set(false);
                }
                break; 
            }

            logger.info("Driver " + this.driverId + " (Car: " + this.car.getIdCar() + ") iniciando monitoramento da rota " + rotaAtual.getIdRota());
            boolean currentRouteStillActive = true;
            while (currentRouteStillActive && this.running.get()) {
                try {
                    if (envSimulator != null) envSimulator.updateThreadActivity(this.driverId);

                    if (this.sumo == null || this.sumo.isClosed()) {
                        logger.warning("Driver " + this.driverId + " (Rota: " + rotaAtual.getIdRota() + ") - Conexão SUMO fechada durante monitoramento.");
                        this.running.set(false); 
                        break;
                    }

                    String vehicleID = this.car.getIdCar();
                    SumoStringList currentVehiclesInSim = (SumoStringList) this.sumo.do_job_get(Vehicle.getIDList());
                    if (currentVehiclesInSim.contains(vehicleID)) {
                        int routeIndex = (int) this.sumo.do_job_get(Vehicle.getRouteIndex(vehicleID));
                        if (routeIndex == -1) { 
                            logger.info("Driver " + this.driverId + " (Car: " + vehicleID + ") completou a rota " + rotaAtual.getIdRota() + " (índice de rota -1).");
                            currentRouteStillActive = false;
                        }
                        // O carro pode ter lógica interna de "drive" que interage com SUMO
                        if (this.car != null && this.running.get()) {
                            // Exemplo: this.car.updateStateAndAct(); // Se o carro tiver tal método
                        }
                    } else {
                        logger.info("Driver " + this.driverId + " (Car: " + vehicleID + ") não está mais na simulação (durante rota " + rotaAtual.getIdRota() + "). Rota considerada completa.");
                        currentRouteStillActive = false;
                    }

                    if (!currentRouteStillActive) {
                        rotaAtual.completeRota(); 
                        this.rotasExecutadas.add(rotaAtual); // Adiciona à lista de executadas
                        logger.info("Driver " + this.driverId + ": Estado da Rota " + rotaAtual.getIdRota() + " marcado como completo e movido para executadas.");
                        processPaymentForFuelStation(rotaAtual); // Passa a rota que acabou de ser completada
                    } else {
                        Thread.sleep(this.car.getAcquisitionRate()); 
                    }

                } catch (InterruptedException e) {
                    logger.warning("Driver " + this.driverId + " interrompido durante monitoramento da rota " + rotaAtual.getIdRota());
                    Thread.currentThread().interrupt();
                    this.running.set(false);
                } catch (it.polito.appeal.traci.TraCIException e) {
                    String msg = e.getMessage() != null ? e.getMessage().toLowerCase() : "";
                    if (msg.contains(this.car.getIdCar().toLowerCase() + "' does not exist") || msg.contains("is not known")) {
                        logger.info("Driver " + this.driverId + " (Car: " + this.car.getIdCar() + ") não existe mais no SUMO (exceção TraCI na rota " + rotaAtual.getIdRota() + "). Rota considerada completa.");
                        currentRouteStillActive = false;
                        rotaAtual.completeRota();
                        this.rotasExecutadas.add(rotaAtual);
                        processPaymentForFuelStation(rotaAtual);
                    } else {
                        logger.log(Level.WARNING, "Driver " + this.driverId + " (Rota: " + rotaAtual.getIdRota() + ") erro TraCI durante monitoramento: " + e.getMessage(), e);
                        this.running.set(false); 
                    }
                } catch (Exception e) {
                    logger.log(Level.WARNING, "Driver " + this.driverId + " (Rota: " + rotaAtual.getIdRota() + ") erro geral durante monitoramento: " + e.getMessage(), e);
                    this.running.set(false); 
                }
            }
            
            if (!this.running.get()) { 
                logger.info("Driver " + this.driverId + " parando após a rota " + rotaAtual.getIdRota());
                break; 
            }

            logger.info("Driver " + this.driverId + " (Car: " + this.car.getIdCar() + ") finalizou o processamento da rota " + rotaAtual.getIdRota());
            isFirstRouteForThisCar = false; 
        } 

        logger.info("Driver " + this.driverId + " (Car: " + this.car.getIdCar() + ") completou todas as suas rotas ou foi interrompido.");
        cleanup();
        if (envSimulator != null) envSimulator.markThreadTerminated(this.driverId);
        logger.info("Driver " + driverId + " encerrado.");
    }

    // O método processPaymentForFuelStation original parecia pegar a última rota da lista
    // rotasExecutadas. Agora é melhor passar a rota que acabou de ser concluída.
    private void processPaymentForFuelStation(Rota rotaConcluida) {
        if (botPayment == null || !this.connectedToBank) {
            logger.warning("Driver " + driverId + " - botPayment indisponível ou não conectado ao banco. Pagamento não processado para rota " + rotaConcluida.getIdRota());
            return;
        }

        // Calcula a distância percorrida (rotaConcluida deve ter essa informação)
        double distanciaPercorridaMetros = rotaConcluida.getActualDistance(); // Supondo que Rota tem este método
        if (distanciaPercorridaMetros <= 0) {
            logger.info("Driver " + driverId + ": Distância percorrida na rota " + rotaConcluida.getIdRota() + " foi zero ou inválida. Nenhum pagamento de combustível.");
            return;
        }
        double distanciaKm = distanciaPercorridaMetros / 1000.0;
        double valorAPagar = distanciaKm * FUEL_PRICE_PER_KM; // FUEL_PRICE_PER_KM deve ser definido

        if (valorAPagar > 0) {
            logger.info("Driver " + driverId + " processando pagamento de R$" + String.format("%.2f", valorAPagar) + 
                       " para Fuel Station (Rota: " + rotaConcluida.getIdRota() + ", Distância: " + String.format("%.2f", distanciaKm) + " km)");
            
            String paymentId = botPayment.processPayment("fuel_station_account_id", valorAPagar, // Substitua pelo ID real da conta do posto
                                     "Combustível Rota " + rotaConcluida.getIdRota() + " Car " + this.car.getIdCar());
            
            if (paymentId != null) {
                logger.info("Driver " + driverId + ": Pagamento de combustível para rota " + rotaConcluida.getIdRota() + " processado com sucesso: " + paymentId);
            } else {
                logger.warning("Driver " + driverId + ": Falha no processamento do pagamento de combustível para rota " + rotaConcluida.getIdRota());
            }
        }
    }
    
    private void cleanup() {
        logger.info("Driver " + driverId + " limpando recursos...");
        if (botPayment != null) {
            botPayment.stopBot(); // Sinaliza para o bot parar
            try {
                botPayment.join(5000); // Espera um pouco pelo bot terminar
                if (botPayment.isAlive()) {
                    logger.warning("Driver " + driverId + ": Timeout esperando BotPayment terminar.");
                }
            } catch (InterruptedException e) {
                logger.warning("Driver " + driverId + ": Interrompido enquanto esperava BotPayment terminar.");
                Thread.currentThread().interrupt();
            }
        }
        if (bankConnection != null && !bankConnection.isClosed()) {
            try {
                this.indrivStream.close();
                this.outdriverStream.close();
                this.bankConnection.close();
                this.connectedToBank = false;
                logger.info("Driver " + driverId + ": Conexão com AlphaBank fechada.");
            } catch (IOException e) {
                logger.warning("Driver " + driverId + ": Erro ao fechar conexão com AlphaBank: " + e.getMessage());
            }
        }
         logger.info("Driver " + driverId + " - Recursos liberados");
    }

    public void stopDriver() {
        logger.info("Driver " + driverId + " recebendo sinal para parar...");
        this.running.set(false);
        this.interrupt(); // Interrompe a thread se estiver em sleep/wait
    }

    // --- Getters e Setters existentes ---
    // (Os locks para rotas podem ser simplificados se a modificação da lista de rotas
    // for feita apenas antes de iniciar a thread do Driver)

    public String getDriverId() { return driverId; }
    public String getNome() { return nome; }
    public Car getCar() { return car; }
    public Account getAccount() { return account; }
    
    public List<Rota> getRotasExecutadas() {
        lockRotasExecutadas.lock();
        try {
            return new ArrayList<>(rotasExecutadas);
        } finally {
            lockRotasExecutadas.unlock();
        }
    }
    // Os métodos getNextRota, startRota, isRotaCompleted, finishCurrentRota
    // que você tinha antes eram para um modelo de simulação de rota diferente (não SUMO-direto).
    // Eles não são usados diretamente na nova lógica de run() que controla o SUMO.
    // Se ainda forem necessários para alguma outra funcionalidade, podem permanecer,
    // mas não devem interferir na execução das rotas SUMO.
}