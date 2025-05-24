package io.sim;

import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.security.PublicKey;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.locks.ReentrantLock;
import java.util.logging.Logger;
import java.util.Base64;
import org.json.JSONObject;

import io.sim.utils.JsonUtil;

/**
 * Classe que representa um motorista no sistema de simulação.
 * Implementa Thread para execução concorrente e atua como cliente para o AlphaBank.
 * Gerencia um carro e rotas a serem executadas.
 */
public class Driver extends Thread {
    
    private static final Logger logger = Logger.getLogger(Driver.class.getName());
    
    // Identificação do motorista
    private final String driverId;
    private final String nome;
    
    // Carro associado ao motorista
    private Car car;
    
    // Conexão com o AlphaBank
    private Socket bankConnection;
    private ObjectOutputStream out;
    private ObjectInputStream in;
    private Account account;
    private boolean connected;
    
    // Gerenciamento de rotas
    private final ArrayList<Rota> rotasAExecutar;
    private Rota rotaEmExecucao;
    private final ArrayList<Rota> rotasExecutadas;
    
    // Locks para controle de acesso às listas de rotas
    private final ReentrantLock lockRotasAExecutar = new ReentrantLock();
    private final ReentrantLock lockRotaEmExecucao = new ReentrantLock();
    private final ReentrantLock lockRotasExecutadas = new ReentrantLock();
    
    // Bot de pagamento para a Fuel Station
    private BotPayment botPayment;
    
    // Controle de execução
    private boolean running;
    private final double FUEL_PRICE_PER_KM = 5.87;
    
    // Utilitário de criptografia
    private EncriptaDecriptaDES sessionEncryptor;
    private String chaveBanco;
    
    /**
     * Construtor principal do Driver.
     * 
     * @param driverId ID do motorista
     * @param nome Nome do motorista
     * @param car Carro associado ao motorista
     * @param bankHost Host do servidor AlphaBank
     * @param bankPort Porta do servidor AlphaBank
     * @param login Login para a conta no AlphaBank
     * @param password Senha para a conta no AlphaBank
     * @param initialBalance Saldo inicial da conta
     */
    public Driver(String driverId, String nome, Car car, 
                 String bankHost, int bankPort, 
                 String login, String password, 
                 double initialBalance) {
        this.driverId = driverId;
        this.nome = nome;
        this.car = car;
        this.rotasAExecutar = new ArrayList<>();
        this.rotasExecutadas = new ArrayList<>();
        this.rotaEmExecucao = null;
        this.running = false;
        this.connected = false;
        
        try {
            // Inicializa o encriptador
            this.chaveBanco = "MTIzNDU2Nzg=";
            this.sessionEncryptor = new EncriptaDecriptaDES(Base64.getDecoder().decode(chaveBanco));
            
            // Cria conta no AlphaBank
            this.account = new Account(login, password, initialBalance);
            
            // Conecta ao servidor AlphaBank
            connectToBank(bankHost, bankPort);
            
            // Inicializa o bot de pagamento
            this.botPayment = new BotPayment(account, bankHost, bankPort);
            
            logger.info("Driver " + driverId + " criado com sucesso");
        } catch (Exception e) {
            logger.severe("Erro ao criar Driver " + driverId + ": " + e.getMessage());
        }
    }
    
    /**
     * Estabelece conexão com o servidor AlphaBank.
     * 
     * @param host Host do servidor
     * @param port Porta do servidor
     * @throws IOException Se ocorrer um erro de conexão
     */
    private void connectToBank(String host, int port) throws IOException {
        try {
            logger.info("Iniciando conexão com AlphaBank...");
            this.bankConnection = new Socket(host, port);
            // Timeout pode ser útil, mas ajuste conforme necessário
            // this.bankConnection.setSoTimeout(10000); // 10 segundos de timeout para operações de socket

            logger.info("Socket do Driver criado. Criando streams...");
            this.out = new ObjectOutputStream(bankConnection.getOutputStream());
            logger.info("Output Stream Criada. Criando Input stream...");
            this.out.flush(); // Flush antes de criar ObjectInputStream
            this.in = new ObjectInputStream(bankConnection.getInputStream());

            // 1. ENVIAR ID DO CLIENTE PARA O SERVIDOR
            String clientId = this.driverId + "_" + UUID.randomUUID().toString(); // Ou apenas this.driverId se for único
            logger.info("Enviando ID do cliente: " + clientId);
            out.writeObject(clientId);
            out.flush();

            // 2. RECEBER CHAVE PÚBLICA RSA DO SERVIDOR (em Base64)
            // ESTA É A PRIMEIRA RESPOSTA DO SERVIDOR AGORA
            String serverRsaPublicKeyBase64 = (String) in.readObject();
            // O log que você já tem ("Resposta de conexão recebida: MIIB...") mostra que esta linha funciona.
            // Remova qualquer log que chame isso de "Resposta de conexão" genérica
            // e adicione um log específico como:
            logger.info("Chave pública RSA do servidor recebida (Base64).");
            PublicKey serverRsaPublicKey = EncriptaDecriptaRSA.getPublicKeyFromBase64(serverRsaPublicKeyBase64);

            // 3. GERAR UMA CHAVE DE SESSÃO DES ALEATÓRIA
            EncriptaDecriptaDES desKeyGenerator = new EncriptaDecriptaDES(); // Construtor padrão gera chave
            byte[] desSessionKeyBytes = desKeyGenerator.getChaveDESBytes();
            
            // Inicializa o sessionEncryptor DESTE CLIENTE com a chave de sessão gerada
            this.sessionEncryptor = new EncriptaDecriptaDES(desSessionKeyBytes); // sessionEncryptor é um atributo da classe Driver
            logger.info("Chave DES de sessão gerada.");

            // 4. CRIPTOGRAFAR A CHAVE DE SESSÃO DES COM A CHAVE PÚBLICA RSA DO SERVIDOR
            byte[] encryptedDesSessionKey = EncriptaDecriptaRSA.criptografarComPublicKey(desSessionKeyBytes, serverRsaPublicKey);

            // 5. ENVIAR A CHAVE DE SESSÃO DES (CRIPTOGRAFADA COM RSA) PARA O SERVIDOR
            out.writeObject(encryptedDesSessionKey);
            out.flush();
            logger.info("Chave DES de sessão criptografada enviada ao servidor.");

            // 6. AGUARDAR CONFIRMAÇÃO DE CONEXÃO SEGURA DO SERVIDOR (CRIPTOGRAFADA COM A CHAVE DES DE SESSÃO)
            String encryptedConfirmation = (String) in.readObject(); // Servidor envia string Base64 criptografada
            String confirmationMessage = this.sessionEncryptor.descriptografar(encryptedConfirmation);

            if (!"CONNECTED_SECURELY".equals(confirmationMessage)) {
                throw new IOException("Falha ao estabelecer conexão segura com o servidor. Resposta de confirmação inválida: " + confirmationMessage);
            }
            this.connected = true; // Defina 'connected' aqui
            logger.info("Driver " + driverId + " conectado de forma segura ao servidor AlphaBank.");

            // Agora, proceda com a autenticação usando a chave de sessão
            sendAuthenticationRequest(); // Este método deve usar this.sessionEncryptor

        } catch (IOException e) {
            logger.severe("Erro de IO ao conectar/negociar chave com AlphaBank: " + e.getMessage());
            throw e; // Re-throw para ser pego pelo construtor do Driver, se necessário
        } catch (ClassNotFoundException e) {
            logger.severe("Erro de classe não encontrada durante negociação: " + e.getMessage());
            throw new IOException("Erro de comunicação (classe não encontrada)", e);
        } catch (Exception e) { 
            logger.severe("Exceção geral durante negociação de chave para Driver " + driverId + ": " + e.getMessage());
            // e.printStackTrace(); // Útil para depuração
            throw new IOException("Erro na configuração da criptografia ou negociação de chave: " + e.getMessage(), e);
        }
    }
    
    /**
     * Envia solicitação de autenticação para o servidor AlphaBank.
     * 
     * @throws IOException Se ocorrer um erro de comunicação
     */
    private void sendAuthenticationRequest() throws IOException {
        try {
            // Cria objeto JSON diretamente com o campo "action" necessário
            JSONObject authJson = new JSONObject();
            authJson.put("action", "AUTHENTICATE");
            authJson.put("login", account.getLogin());
            authJson.put("password", account.getSenha());
            authJson.put("clientType", "DRIVER");
            
            String authRequest = authJson.toString();
            logger.info("Enviando requisição de autenticação: " + authRequest);
            
            // Criptografa a solicitação
            String encryptedRequest = sessionEncryptor.criptografar(authRequest);
            
            // Envia para o servidor
            out.writeObject(encryptedRequest);
            out.flush();
            
            // Aguarda resposta
            String encryptedResponse = (String) in.readObject();
            String response = sessionEncryptor.descriptografar(encryptedResponse);
            logger.info("Resposta de autenticação recebida: " + response);
            
            // Verifica se a autenticação foi bem-sucedida
            if (!response.contains("\"status\":\"success\"")) {
                throw new IOException("Falha na autenticação: " + response);
            }
            
            logger.info("Autenticação bem-sucedida para o Driver " + driverId);
        } catch (Exception e) {
            logger.severe("Erro na autenticação: " + e.getMessage());
            throw new IOException("Erro na autenticação", e);
        }
    }
    
    /**
     * Método principal da thread.
     * Gerencia a execução de rotas e a comunicação com o AlphaBank.
     */
    @Override
    public void run() {
        this.running = true;
        
        // Inicia o bot de pagamento
        this.botPayment.start();
        
        logger.info("Driver " + driverId + " iniciado");
        
        while (running) {
            try {
                // Verifica se há uma rota em execução
                if (rotaEmExecucao == null) {
                    // Tenta obter uma nova rota para executar
                    Rota proximaRota = getNextRota();
                    
                    if (proximaRota != null) {
                        // Inicia a execução da rota
                        startRota(proximaRota);
                    }
                } else {
                    // Verifica se a rota atual foi concluída
                    if (isRotaCompleted()) {
                        // Finaliza a rota atual
                        finishCurrentRota();
                        
                        // Processa pagamento para a Fuel Station
                        processPaymentForFuelStation();
                    }
                }
                
                // Verifica se há mensagens do servidor AlphaBank
                if (connected && bankConnection.getInputStream().available() > 0) {
                    processServerMessage();
                }
                
                // Pausa para evitar uso excessivo de CPU
                Thread.sleep(1000);
            } catch (InterruptedException e) {
                // Interrupção normal, verifica se deve continuar
                if (!running) {
                    break;
                }
            } catch (Exception e) {
                logger.severe("Erro no loop principal do Driver " + driverId + ": " + e.getMessage());
                
                // Tenta reconectar em caso de erro de conexão
                if (e instanceof IOException && connected) {
                    try {
                        reconnectToBank();
                    } catch (Exception reconnectError) {
                        logger.severe("Falha ao reconectar ao AlphaBank: " + reconnectError.getMessage());
                    }
                }
            }
        }
        
        // Limpa recursos ao encerrar
        cleanup();
    }
    
    /**
     * Obtém a próxima rota a ser executada.
     * 
     * @return Próxima rota ou null se não houver rotas disponíveis
     */
    private Rota getNextRota() {
        lockRotasAExecutar.lock();
        try {
            if (rotasAExecutar.isEmpty()) {
                return null;
            }
            
            return rotasAExecutar.remove(0);
        } finally {
            lockRotasAExecutar.unlock();
        }
    }
    
    /**
     * Inicia a execução de uma rota.
     * 
     * @param rota Rota a ser iniciada
     */
    private void startRota(Rota rota) {
        lockRotaEmExecucao.lock();
        try {
            // Atribui a rota ao motorista e carro
            if (rota.assignRota(this.driverId, this.car.getIdCar())) {
                // Inicia a execução da rota
                if (rota.startRota()) {
                    this.rotaEmExecucao = rota;
                    logger.info("Driver " + driverId + " iniciou a execução da rota " + rota.getIdRota());
                    
                    // Aqui poderia ter código para iniciar a navegação no SUMO
                    // Por exemplo, enviar comandos para o carro seguir a rota
                } else {
                    logger.warning("Falha ao iniciar a rota " + rota.getIdRota());
                    addRotaToExecutadas(rota);
                }
            } else {
                logger.warning("Falha ao atribuir a rota " + rota.getIdRota() + " ao motorista " + driverId);
                addRotaToExecutadas(rota);
            }
        } finally {
            lockRotaEmExecucao.unlock();
        }
    }
    
    /**
     * Verifica se a rota atual foi concluída.
     * 
     * @return true se a rota foi concluída, false caso contrário
     */
    private boolean isRotaCompleted() {
        // Esta é uma implementação simplificada
        // Em um cenário real, verificaria o progresso da rota no SUMO
        
        // Simulação: 10% de chance de concluir a rota a cada verificação
        return Math.random() < 0.1;
    }
    
    /**
     * Finaliza a rota atual e a move para a lista de rotas executadas.
     */
    private void finishCurrentRota() {
        lockRotaEmExecucao.lock();
        try {
            if (rotaEmExecucao != null) {
                // Marca a rota como concluída
                rotaEmExecucao.completeRota();
                
                // Adiciona os dados de condução do carro à rota
                for (DrivingData data : car.getDrivingRepport()) {
                    rotaEmExecucao.addDrivingData(data);
                }
                
                logger.info("Driver " + driverId + " concluiu a rota " + rotaEmExecucao.getIdRota());
                
                // Move a rota para a lista de rotas executadas
                addRotaToExecutadas(rotaEmExecucao);
                
                // Limpa a referência à rota em execução
                rotaEmExecucao = null;
            }
        } finally {
            lockRotaEmExecucao.unlock();
        }
    }
    
    /**
     * Adiciona uma rota à lista de rotas executadas.
     * 
     * @param rota Rota a ser adicionada
     */
    public void addRotaToExecutadas(Rota rota) {
        lockRotasExecutadas.lock();
        try {
            rotasExecutadas.add(rota);
        } finally {
            lockRotasExecutadas.unlock();
        }
    }
    
    /**
     * Processa o pagamento para a Fuel Station com base na distância percorrida.
     */
    private void processPaymentForFuelStation() {
        lockRotasExecutadas.lock();
        try {
            // Obtém a última rota executada
            if (!rotasExecutadas.isEmpty()) {
                Rota ultimaRota = rotasExecutadas.get(rotasExecutadas.size() - 1);
                
                // Calcula a distância percorrida em km
                double distanciaKm = ultimaRota.getActualDistance() / 1000.0;
                
                // Calcula o valor a pagar (R$ 5,87 por km)
                double valorPagamento = distanciaKm * FUEL_PRICE_PER_KM;
                
                // Processa o pagamento através do BotPayment
                // Aqui assumimos que existe uma conta da Fuel Station no AlphaBank
                Account fuelStationAccount = new Account("fuel_station", "fuel_password",0.0);
                
                String paymentId = botPayment.processPayment(
                        fuelStationAccount, 
                        valorPagamento, 
                        "Pagamento de combustível - Rota: " + ultimaRota.getIdRota() + " - Distância: " + distanciaKm + " km");
                
                if (paymentId != null) {
                    logger.info("Pagamento de combustível realizado com sucesso: " + paymentId + " - Valor: R$ " + valorPagamento);
                } else {
                    logger.warning("Falha no pagamento de combustível para a rota " + ultimaRota.getIdRota());
                }
            }
        } finally {
            lockRotasExecutadas.unlock();
        }
    }
    
    /**
     * Processa mensagens recebidas do servidor AlphaBank.
     * 
     * @throws Exception Se ocorrer um erro no processamento
     */
    private void processServerMessage() throws Exception {
        try {
            // Lê a mensagem criptografada
            String encryptedMessage = (String) in.readObject();
            
            // Descriptografa a mensagem
            String message = sessionEncryptor.descriptografar(encryptedMessage);
            
            logger.info("Mensagem recebida do AlphaBank: " + message);
            
            // Aqui seria implementada a lógica para processar diferentes tipos de mensagens
            // Por exemplo, confirmações de pagamento, notificações, etc.
        } catch (Exception e) {
            logger.severe("Erro ao processar mensagem do AlphaBank: " + e.getMessage());
            throw e;
        }
    }
    
    /**
     * Tenta reconectar ao servidor AlphaBank.
     * 
     * @throws Exception Se ocorrer um erro na reconexão
     */
    private void reconnectToBank() throws Exception {
        logger.info("Tentando reconectar ao servidor AlphaBank...");
        
        // Fecha conexão atual se existir
        if (bankConnection != null && !bankConnection.isClosed()) {
            try {
                bankConnection.close();
            } catch (IOException e) {
                // Ignora erros ao fechar
            }
        }
        
        this.connected = false;
        
        // Tenta reconectar
        String host = bankConnection.getInetAddress().getHostName();
        int port = bankConnection.getPort();
        
        // Reconecta
        connectToBank(host, port);
    }
    
    /**
     * Limpa recursos ao encerrar o driver.
     */
    private void cleanup() {
        // Para o bot de pagamento
        if (botPayment != null) {
            botPayment.stopBot();
        }
        
        // Fecha conexão com o banco
        try {
            if (out != null) out.close();
            if (in != null) in.close();
            if (bankConnection != null && !bankConnection.isClosed()) {
                bankConnection.close();
            }
        } catch (IOException e) {
            logger.severe("Erro ao fechar conexões: " + e.getMessage());
        }
        
        logger.info("Driver " + driverId + " encerrado");
    }
    
    /**
     * Adiciona uma rota à lista de rotas a executar.
     * 
     * @param rota Rota a ser adicionada
     */
    public void addRota(Rota rota) {
        lockRotasAExecutar.lock();
        try {
            rotasAExecutar.add(rota);
            logger.info("Rota " + rota.getIdRota() + " adicionada ao Driver " + driverId);
        } finally {
            lockRotasAExecutar.unlock();
        }
    }
    
    /**
     * Para a execução do driver.
     */
    public void stopDriver() {
        this.running = false;
        this.interrupt();
    }
    
    // Getters e setters
    
    public String getDriverId() {
        return driverId;
    }
    
    public String getNome() {
        return nome;
    }
    
    public Car getCar() {
        return car;
    }
    
    public void setCar(Car car) {
        this.car = car;
    }
    
    public Account getAccount() {
        return account;
    }
    
    public List<Rota> getRotasAExecutar() {
        lockRotasAExecutar.lock();
        try {
            return new ArrayList<>(rotasAExecutar);
        } finally {
            lockRotasAExecutar.unlock();
        }
    }
    
    public Rota getRotaEmExecucao() {
        lockRotaEmExecucao.lock();
        try {
            return rotaEmExecucao;
        } finally {
            lockRotaEmExecucao.unlock();
        }
    }
    
    public List<Rota> getRotasExecutadas() {
        lockRotasExecutadas.lock();
        try {
            return new ArrayList<>(rotasExecutadas);
        } finally {
            lockRotasExecutadas.unlock();
        }
    }
    
    public boolean isConnected() {
        return connected;
    }
    
    public boolean isRunning() {
        return running;
    }
}
