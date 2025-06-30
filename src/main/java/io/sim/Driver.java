package io.sim;

// import java.io.DataInputStream;
// import java.io.DataOutputStream;
// import java.io.IOException;
// import java.io.ObjectInputStream;
// import java.io.ObjectOutputStream;
// import java.net.Socket;
// import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.json.JSONObject;

import de.tudresden.sumo.cmd.Route;
import de.tudresden.sumo.cmd.Vehicle;
import de.tudresden.sumo.objects.SumoStringList;
import io.sim.utils.JsonUtil;
import it.polito.appeal.traci.SumoTraciConnection;

/**
 * Classe que representa um motorista no sistema.
 * Responsável por gerenciar um carro e executar rotas.
 */
public class Driver extends Thread {

    private static final Logger logger = Logger.getLogger(Driver.class.getName());
    
    // Constantes para retry
    private static final int maxRetriesRouteInit = 5;
    private static final int initialRetryDelayRouteMs = 1000;
    private static final int initialRetryDelayRouteInitMs = 1000;
    
    // Atributos do motorista
    private String driverId;
    private String name;
    private Car car;
    private Account account;
    private BotPayment botPayment;
    
    // Atributos de conexão com o banco
    private String bankHost;
    private int bankPort;
    private String login;
    private String password;
    private double initialBalance;
    private boolean connectedToBank = false;
    private AlphaBank alphaBank;
    
    // Atributos de conexão com o SUMO
    private SumoTraciConnection sumo;
    
    // Atributos de controle
    private AtomicBoolean running = new AtomicBoolean(false);
    
    // Referência ao EnvSimulator para monitoramento e acesso ao sumoLock
    private EnvSimulator envSimulator;
    
    // Listas de rotas
    private List<Rota> rotasAExecutar;
    private List<Rota> rotasEmExecucao;
    private List<Rota> rotasExecutadas;

    /**
     * Construtor da classe Driver.
     * * @param driverId ID do motorista
     * @param name Nome do motorista
     * @param car Carro do motorista
     * @param bankHost Host do banco
     * @param bankPort Porta do banco
     * @param login Login do motorista
     * @param password Senha do motorista
     * @param initialBalance Saldo inicial
     * @param sumo Conexão com o SUMO
     * @param envSimulator Referência ao EnvSimulator
     * @param alphaBank Referência ao AlphaBank
     */
    public Driver(String driverId, String name, Car car, String bankHost, int bankPort, String login, String password, double initialBalance, SumoTraciConnection sumo, EnvSimulator envSimulator, AlphaBank alphaBank) {
        this.driverId = driverId;
        this.name = name;
        this.car = car;
        this.bankHost = bankHost;
        this.bankPort = bankPort;
        this.login = login;
        this.password = password;
        this.initialBalance = initialBalance;
        this.sumo = sumo;
        this.envSimulator = envSimulator;
        this.alphaBank = alphaBank;
        
        // Inicializa as listas de rotas
        this.rotasAExecutar = new ArrayList<>();
        this.rotasEmExecucao = new ArrayList<>();
        this.rotasExecutadas = new ArrayList<>();
        
    }
    
    /**
     * Conecta ao banco e cria a conta do motorista.
     */
    private void connectToBankAndCreateAccount() {
        try {
            logger.info("Driver " + driverId + " conectando ao banco em " + bankHost + ":" + bankPort);
            
            // Cria a conta do motorista
            this.account = new Account(password, login, initialBalance);
            
            // Adiciona a conta ao AlphaBank antes de tentar autenticar
            if (alphaBank != null) {
                boolean added = alphaBank.addAccount(account);
                logger.info("Driver " + driverId + " conta adicionada ao AlphaBank: " + added);
                if (!added) {
                    logger.warning("Driver " + driverId + " falha ao adicionar conta ao AlphaBank");
                }
            } else {
                logger.severe("Driver " + driverId + " alphaBank é NULO!");
            }
            
            // Cria o BotPayment para processar pagamentos
            long defaultPaymentInterval = 5000;
            this.botPayment = new BotPayment(account, bankHost, bankPort, defaultPaymentInterval,true);
            
            // Marca como conectado ao banco
            this.connectedToBank = true;
            
            logger.info("Driver " + driverId + " conectado ao banco com sucesso");
        } catch (Exception e) {
            logger.log(Level.SEVERE, "Driver " + driverId + " falha ao conectar ao banco: " + e.getMessage(), e);
            this.connectedToBank = false;
        }
    }

    /**
     * Adiciona uma rota à lista de rotas a executar.
     * * @param rota Rota a ser adicionada
     */
    public void addRota(Rota rota) {
        if (rota != null && rota.isOn()) {
            synchronized (rotasAExecutar) {
                rotasAExecutar.add(rota);
            }
            logger.info("Driver " + driverId + " rota adicionada: " + rota.getIdRota());
        }
    }

    @Override
    public void run() {
        // Conecta ao banco e cria a conta
        connectToBankAndCreateAccount();
        // Verifica se a conexão inicial com o banco e a criação da conta foram bem-sucedidas
        if (!this.connectedToBank && account == null) {
            logger.severe("Driver " + driverId + " não pode iniciar. Falha na conexão/autenticação inicial com o banco ou conta não criada.");
            if (envSimulator != null) envSimulator.markThreadTerminated(this.driverId); // Notifica o EnvSimulator
            return; // Encerra a thread do Driver se não pôde se conectar/criar conta
        }
        this.running.set(true); // Define que o Driver está em execução

        // Inicia o BotPayment SE a conexão com o banco foi bem sucedida E o bot foi criado
        if (this.botPayment != null && this.connectedToBank) {
            this.botPayment.start(); // Inicia a thread do BotPayment
        } else if (this.botPayment == null) {
            logger.warning("Driver " + driverId + ": BotPayment não inicializado.");
        } else { // BotPayment existe mas não está conectado ao banco (cenário menos provável com a lógica atual)
            logger.warning("Driver " + driverId + ": Não conectado ao banco, BotPayment não será iniciado.");
        }

        logger.info("Driver " + this.driverId + " (Car: " + this.car.getIdCar() + ") processando rotas sequencialmente.");
        
        List<Rota> rotasParaProcessar = getRotasAExecutar(); 
        boolean isFirstRouteForThisCar = true;

        for (Rota rotaAtual : rotasParaProcessar) {
            if (!this.running.get()) {
                logger.info("Driver " + this.driverId + " interrompido antes da rota " + rotaAtual.getIdRota());
                break;
            }

            if (envSimulator != null) envSimulator.updateThreadStatus(this.driverId);
            logger.info("Driver " + this.driverId + " iniciando processamento da rota " + rotaAtual.getIdRota());
            
            synchronized (rotasAExecutar) {
                rotasAExecutar.remove(rotaAtual);
            }
            synchronized (rotasEmExecucao) {
                rotasEmExecucao.add(rotaAtual);
            }
            
            if (envSimulator != null && envSimulator.getMobilityCompany() != null) {
                // envSimulator.getMobilityCompany().iniciarExecucaoRota(rotaAtual.getIdRota(), this.car.getIdCar());
            }
            
            logger.info("Driver " + this.driverId + " moveu a rota " + rotaAtual.getIdRota() + " para rotasEmExecucao");

            boolean routeInitializedSuccessfully = false;
            int attempts = 0;
            int currentRetryDelay = initialRetryDelayRouteInitMs;

            while (attempts < maxRetriesRouteInit && !routeInitializedSuccessfully && this.running.get()) {
                try {
                    if (attempts > 0) {
                        logger.info("Driver " + this.driverId + " (Rota: " + rotaAtual.getIdRota() + 
                                    ") tentando inicialização novamente (tentativa " + (attempts + 1) + "/" + maxRetriesRouteInit + ")");
                        Thread.sleep(currentRetryDelay);
                        currentRetryDelay *= 2;
                    }

                    // ########## INÍCIO DO BLOCO SINCRONIZADO 1 ##########
                    synchronized (this.envSimulator.getSumoLock()) {
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
                        
                        logger.info("Driver " + this.driverId + " (Car: " + vehicleID + ") definindo/adicionando rota SUMO: " + routeID);
                        this.sumo.do_job_set(Route.add(routeID, edgeList)); 

                        if (isFirstRouteForThisCar) {
                            SumoStringList existingVehicles = (SumoStringList) this.sumo.do_job_get(Vehicle.getIDList());
                            if (existingVehicles.contains(vehicleID)) {
                                logger.warning("Driver " + this.driverId + ": Veículo " + vehicleID + 
                                            " já existe no SUMO (adicionado pelo EnvSimulator). Definindo rota " + routeID + " para ele.");
                                this.sumo.do_job_set(Vehicle.setRouteID(vehicleID, routeID));
                            } else {
                                logger.severe("Driver " + this.driverId + ": Veículo " + vehicleID + 
                                            " NÃO encontrado no SUMO, embora devesse ter sido adicionado pelo EnvSimulator. Tentando adicionar com addFull (PODE FALHAR).");
                                this.sumo.do_job_set(Vehicle.addFull(vehicleID, routeID, "DEFAULT_VEHTYPE", "now", 
                                                            "0", "0", "0", "current", "max", "current",
                                                            "", "", "", this.car.getPersonCapacity(), this.car.getPersonNumber()));
                            }
                        } else {
                            logger.info("Driver " + this.driverId + " (rota subsequente) atribuindo rota " + routeID + " ao veículo existente " + vehicleID);
                            this.sumo.do_job_set(Vehicle.setRouteID(vehicleID, routeID));
                        }
                        this.sumo.do_job_set(Vehicle.setColor(vehicleID, this.car.getColorCar()));
                        logger.info("Driver " + this.driverId + " (Car: " + vehicleID + ") configurado para rota " + routeID);
                    }
                    // ########## FIM DO BLOCO SINCRONIZADO 1 ##########
                    
                    routeInitializedSuccessfully = true;

                } catch (InterruptedException e) {
                    logger.warning("Driver " + this.driverId + " interrompido durante inicialização da rota " + rotaAtual.getIdRota());
                    Thread.currentThread().interrupt();
                    this.running.set(false); 
                    break; 
                } catch (Exception e) {
                    attempts++;
                    logger.log(Level.WARNING, "Driver " + this.driverId + " (Rota: " + rotaAtual.getIdRota() + 
                                        ") falha na inicialização (tentativa " + attempts + "/" + maxRetriesRouteInit + "): " + e.getMessage(), e);
                    // A verificação de sumo.isClosed() deve estar dentro do bloco synchronized.
                    // Se chegar aqui, o erro foi outro. Podemos apenas checar se a flag 'running' foi setada para false.
                    if (this.running.get() == false) {
                        break;
                    }
                    if (attempts >= maxRetriesRouteInit) {
                        logger.severe("Driver " + this.driverId + " (Rota: " + rotaAtual.getIdRota() + 
                                    ") falhou ao inicializar após " + maxRetriesRouteInit + " tentativas.");
                        this.running.set(false);
                    }
                }
            }

            if (!routeInitializedSuccessfully || !this.running.get()) {
                if (this.running.get()) {
                    logger.severe("Driver " + this.driverId + " não conseguiu inicializar a rota " + rotaAtual.getIdRota() + ". Parando driver.");
                    this.running.set(false);
                }
                synchronized (rotasEmExecucao) { rotasEmExecucao.remove(rotaAtual); }
                synchronized (rotasExecutadas) { rotasExecutadas.add(rotaAtual); }
                if (envSimulator != null && envSimulator.getMobilityCompany() != null) {
                    // envSimulator.getMobilityCompany().finalizarExecucaoRota(rotaAtual.getIdRota(), this.car.getIdCar(), Rota.Status.FAILED);
                }
                break;
            }

            logger.info("Driver " + this.driverId + " (Car: " + this.car.getIdCar() + ") iniciando monitoramento da rota " + rotaAtual.getIdRota());
            boolean currentRouteStillActive = true;
            while (currentRouteStillActive && this.running.get()) {
                try {
                    if (envSimulator != null) envSimulator.updateThreadStatus(this.driverId);

                    // ########## INÍCIO DO BLOCO SINCRONIZADO 2 ##########
                    synchronized(this.envSimulator.getSumoLock()) {
                        if (this.sumo == null || this.sumo.isClosed()) {
                            logger.warning("Driver " + this.driverId + " (Rota: " + rotaAtual.getIdRota() + 
                                        ") - Conexão SUMO fechada durante monitoramento.");
                            this.running.set(false); 
                            break;
                        }

                        String vehicleID = this.car.getIdCar();
                        SumoStringList currentVehiclesInSim = (SumoStringList) this.sumo.do_job_get(Vehicle.getIDList());
                        
                        if (currentVehiclesInSim.contains(vehicleID)) {
                            int routeIndex = -2;
                            try {
                                Object routeIndexObj = this.sumo.do_job_get(Vehicle.getRouteIndex(vehicleID));
                                if (routeIndexObj instanceof Integer) {
                                    routeIndex = (Integer) routeIndexObj;
                                } else {
                                    logger.warning("Driver " + this.driverId + ": getRouteIndex retornou tipo inesperado: " + (routeIndexObj != null ? routeIndexObj.getClass().getName() : "null"));
                                }
                            } catch (it.polito.appeal.traci.TraCIException e) {
                                String msg = e.getMessage() != null ? e.getMessage().toLowerCase() : "";
                                if (msg.contains(vehicleID.toLowerCase() + "' is not known")) {
                                    logger.info("Driver " + this.driverId + " (Car: " + vehicleID + ") se tornou 'not known' durante monitoramento da rota " + rotaAtual.getIdRota() + ". Rota considerada completa/falha.");
                                    routeIndex = -1;
                                } else {
                                    throw e;
                                }
                            }
                            
                            if (routeIndex == -1) { 
                                logger.info("Driver " + this.driverId + " (Car: " + vehicleID + ") completou a rota " + 
                                        rotaAtual.getIdRota() + " (índice de rota -1).");
                                currentRouteStillActive = false;
                            }
                        } else {
                            logger.info("Driver " + this.driverId + " (Car: " + vehicleID + 
                                    ") não está mais na simulação (durante rota " + rotaAtual.getIdRota() + 
                                    "). Rota considerada completa/falha.");
                            currentRouteStillActive = false;
                        }
                    }
                    // ########## FIM DO BLOCO SINCRONIZADO 2 ##########

                    if (!currentRouteStillActive) {
                        rotaAtual.completeRota();
                        
                        synchronized (rotasEmExecucao) {
                            rotasEmExecucao.remove(rotaAtual);
                        }
                        synchronized (rotasExecutadas) {
                            rotasExecutadas.add(rotaAtual);
                        }
                        
                        if (envSimulator != null && envSimulator.getMobilityCompany() != null) {
                            // envSimulator.getMobilityCompany().finalizarExecucaoRota(rotaAtual.getIdRota(), this.car.getIdCar());
                        }
                        
                        logger.info("Driver " + this.driverId + ": Estado da Rota " + rotaAtual.getIdRota() + 
                                " marcado como completo e movido para executadas.");
                        processPaymentForFuelStation(rotaAtual);
                    } else {
                        // A pausa deve ser fora do bloco synchronized para não prender o lock
                        Thread.sleep(Math.max(500, this.car.getAcquisitionRate()));
                    }

                } catch (InterruptedException e) {
                    logger.warning("Driver " + this.driverId + " interrompido durante monitoramento da rota " + rotaAtual.getIdRota());
                    Thread.currentThread().interrupt();
                    this.running.set(false);
                } catch (it.polito.appeal.traci.TraCIException e) {
                    String msg = e.getMessage() != null ? e.getMessage().toLowerCase() : "";
                    if (msg.contains(this.car.getIdCar().toLowerCase() + "' does not exist") || 
                        msg.contains(this.car.getIdCar().toLowerCase() + "' is not known")) {
                        logger.info("Driver " + this.driverId + " (Car: " + this.car.getIdCar() + 
                                ") não existe mais no SUMO (exceção TraCI na rota " + rotaAtual.getIdRota() + 
                                "). Rota considerada completa/falha.");
                        currentRouteStillActive = false;
                        rotaAtual.completeRota();
                        synchronized (rotasEmExecucao) { rotasEmExecucao.remove(rotaAtual); }
                        synchronized (rotasExecutadas) { rotasExecutadas.add(rotaAtual); }
                        processPaymentForFuelStation(rotaAtual);
                    } else {
                        logger.log(Level.WARNING, "Driver " + this.driverId + " (Rota: " + rotaAtual.getIdRota() + 
                                            ") erro TraCI durante monitoramento: " + e.getMessage(), e);
                        this.running.set(false);
                    }
                } catch (Exception e) {
                    logger.log(Level.WARNING, "Driver " + this.driverId + " (Rota: " + rotaAtual.getIdRota() + 
                                        ") erro geral durante monitoramento: " + e.getMessage(), e);
                    this.running.set(false);
                }
            }
            
            if (!this.running.get() && currentRouteStillActive) { 
                logger.info("Driver " + this.driverId + " parando DURANTE a rota " + rotaAtual.getIdRota());
                rotaAtual.cancelRota();
                synchronized (rotasEmExecucao) { rotasEmExecucao.remove(rotaAtual); }
                synchronized (rotasExecutadas) { rotasExecutadas.add(rotaAtual); }
                // if (envSimulator != null && envSimulator.getMobilityCompany() != null) {
                //    envSimulator.getMobilityCompany().finalizarExecucaoRota(rotaAtual.getIdRota(), this.car.getIdCar(), Rota.Status.ABORTED);
                // }
            } else if (routeInitializedSuccessfully) {
                logger.info("Driver " + this.driverId + " (Car: " + this.car.getIdCar() + ") finalizou o processamento da rota " + rotaAtual.getIdRota());
            }
            
            isFirstRouteForThisCar = false;
        }

        logger.info("Driver " + this.driverId + " (Car: " + this.car.getIdCar() + ") completou todas as suas rotas de missão ou foi interrompido.");
        cleanup();
        if (envSimulator != null) envSimulator.markThreadTerminated(this.driverId);
        logger.info("Driver " + driverId + " encerrado.");
    }

    private void processPaymentForFuelStation(Rota rotaConcluida) {
        if (botPayment == null || !this.connectedToBank) {
            logger.warning("Driver " + driverId + " - botPayment indisponível ou não conectado ao banco. Pagamento não processado para rota " + rotaConcluida.getIdRota());
            return;
        }

        try {
            double valorPagamento = 100.0;
            
            java.util.Map<String, Object> paymentData = new java.util.HashMap<>();
            paymentData.put("driverId", this.driverId);
            paymentData.put("carId", this.car.getIdCar());
            paymentData.put("rotaId", rotaConcluida.getIdRota());
            paymentData.put("valor", valorPagamento);
            paymentData.put("timestamp", System.currentTimeMillis());
            
            String paymentJson = JsonUtil.toJson(paymentData);
            
            String success = botPayment.processPayment("fuel_station", valorPagamento, paymentJson);
            
            if (success != null) {
                logger.info("Driver " + driverId + " - Pagamento de combustível processado com sucesso para rota " + rotaConcluida.getIdRota());
            } else {
                logger.warning("Driver " + driverId + " - Falha ao processar pagamento de combustível para rota " + rotaConcluida.getIdRota());
            }
        } catch (Exception e) {
            logger.log(Level.SEVERE, "Driver " + driverId + " - Erro ao processar pagamento de combustível: " + e.getMessage(), e);
        }
    }
    
    private void cleanup() {
        logger.info("Driver " + driverId + " - Limpando recursos");
        
        if (botPayment != null) {
            try {
                botPayment.stopBot();
                logger.info("Driver " + driverId + " - BotPayment encerrado");
            } catch (Exception e) {
                logger.log(Level.WARNING, "Driver " + driverId + " - Erro ao encerrar BotPayment: " + e.getMessage(), e);
            }
        }
        
        if (car != null) {
            try {
                car.cleanup();
                logger.info("Driver " + driverId + " - Carro encerrado");
            } catch (Exception e) {
                logger.log(Level.WARNING, "Driver " + driverId + " - Erro ao encerrar carro: " + e.getMessage(), e);
            }
        }
        
        this.connectedToBank = false;
        this.running.set(false);
    }
    
    public String getDriverId() {
        return driverId;
    }
    
    public String getNome() {
        return name;
    }
    
    public Car getCar() {
        return car;
    }
    
    public Account getAccount() {
        return account;
    }
    
    public List<Rota> getRotasAExecutar() {
        synchronized (rotasAExecutar) {
            return new ArrayList<>(rotasAExecutar);
        }
    }
    
    public List<Rota> getRotasEmExecucao() {
        synchronized (rotasEmExecucao) {
            return new ArrayList<>(rotasEmExecucao);
        }
    }
    
    public List<Rota> getRotasExecutadas() {
        synchronized (rotasExecutadas) {
            return new ArrayList<>(rotasExecutadas);
        }
    }
    
    public boolean isConnectedToBank() {
        return connectedToBank;
    }
    
    public boolean isRunning() {
        return running.get();
    }
    
    /**
     * Para o motorista de forma graciosa.
     */
    public void stopDriver() {
        this.running.set(false);
        // MODIFICAÇÃO: Adicionado para acordar a thread se ela estiver em sleep.
        this.interrupt();
    }
}