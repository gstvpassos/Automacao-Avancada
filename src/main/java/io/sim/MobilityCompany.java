package io.sim;

import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.logging.Level; 
import java.util.logging.Logger;

import io.sim.utils.JsonUtil;

/**
 * Classe que representa uma empresa de mobilidade que gerencia carros e rotas.
 * Atua como um servidor para receber conexões dos carros e processar dados de condução.
 * ESTA VERSÃO FOI REFATORADA PARA SER TOTALMENTE THREAD-SAFE.
 */
public class MobilityCompany extends Thread {
    private static final Logger logger = Logger.getLogger(MobilityCompany.class.getName()); 
    private CountDownLatch readyLatch;

    private String companyId;
    private int serverPort;
    private ServerSocket serverSocket;
    private volatile boolean running = false;
    private ExecutorService carClientExecutorService;

    private AlphaBank alphaBankServer;
    private Account companyAccount;    
    
    private String rotasXmlPath;
    
    // MUDANÇA #1: Usar coleções seguras para threads (thread-safe).
    private List<Rota> rotasDisponiveis;
    private List<Rota> rotasEmExecucao;
    private List<Rota> rotasExecutadas;
    
    private Map<String, Socket> carConnections;
    private Map<String, ArrayList<DrivingData>> carDrivingReports;
    private Map<String, Rota> carRotaMap;
    private Map<String, EncriptaDecriptaDES> carSessionEncryptors;

    private List<DrivingDataListener> drivingDataListeners;

    private EncriptaDecriptaRSA rsaHandlerCompany;
    private BotPayment botPayment;
    private String alphaBankHost;

    public MobilityCompany(String companyId, int serverPort, AlphaBank alphaBankServer, 
                           Account companyAccount, String rotasXmlPath, 
                           String alphaBankHost, int alphaBankPort, CountDownLatch readyLatch) {
        this.companyId = companyId;
        this.serverPort = serverPort;
        this.alphaBankServer = alphaBankServer;
        this.companyAccount = companyAccount;
        this.rotasXmlPath = rotasXmlPath;
        this.alphaBankHost = alphaBankHost;

        // Inicializa as coleções com implementações thread-safe
        this.readyLatch = readyLatch;
        this.rotasDisponiveis = Collections.synchronizedList(new ArrayList<>());
        this.rotasEmExecucao = Collections.synchronizedList(new ArrayList<>());
        this.rotasExecutadas = Collections.synchronizedList(new ArrayList<>());
        this.carConnections = new ConcurrentHashMap<>();
        this.carDrivingReports = new ConcurrentHashMap<>(); 
        this.carRotaMap = new ConcurrentHashMap<>();
        this.carSessionEncryptors = new ConcurrentHashMap<>();
        this.drivingDataListeners = new ArrayList<>(); // Não precisa ser sync se for modificada apenas antes do run()

        // Usa um FixedThreadPool para mais estabilidade sob alta carga
        this.carClientExecutorService = Executors.newFixedThreadPool(50);
        
        try {
            this.rsaHandlerCompany = new EncriptaDecriptaRSA();
            logger.info("MobilityCompany " + companyId + " RSA KeyPair gerado.");
        } catch (Exception e) {
            logger.log(Level.SEVERE, "MobilityCompany " + companyId + " falha ao gerar RSA KeyPair.", e);
            throw new RuntimeException("Falha ao inicializar RSA para MobilityCompany", e);
        }
        
        try {
            int actualAlphaBankPort = (alphaBankServer != null && alphaBankPort <= 0) ? alphaBankServer.getServerPort() : alphaBankPort;
            if (actualAlphaBankPort <= 0) {
                 throw new IllegalArgumentException("Porta do AlphaBank inválida: " + actualAlphaBankPort);
            }
            this.botPayment = new BotPayment(companyAccount, this.alphaBankHost, actualAlphaBankPort);
            logger.info("MobilityCompany " + companyId + " BotPayment criado para AlphaBank em " + this.alphaBankHost + ":" + actualAlphaBankPort);

        } catch (Exception e) {
            logger.log(Level.SEVERE, "MobilityCompany " + companyId + " falha ao criar um bot de pagamentos: " + e.getMessage(), e);
        }
    }

    /**
     * Carrega todas as rotas do arquivo XML para a lista rotasDisponiveis.
     */
    public void carregarRotas() {
        try {
            logger.info("MobilityCompany " + companyId + " carregando rotas do arquivo: " + rotasXmlPath);
            for (int i = 1; i <= 200; i++) {
                String rotaId = "Rota" + String.format("%03d", i);
                // Assume que o construtor de Rota está corrigido para encontrar o ID correto (CAR1, CAR2, etc.)
                Rota rota = new Rota(rotasXmlPath, rotaId);
                
                if (rota.isOn()) {
                    rotasDisponiveis.add(rota);
                } else {
                    logger.warning("MobilityCompany " + companyId + " falha ao carregar rota: " + rotaId);
                }
            }
            logger.info("MobilityCompany " + companyId + " total de rotas carregadas: " + rotasDisponiveis.size());
        } catch (Exception e) {
            logger.log(Level.SEVERE, "MobilityCompany " + companyId + " erro ao carregar rotas: " + e.getMessage(), e);
        }
    }
    
    public interface DrivingDataListener {
        void onNewDrivingData(DrivingData data);
    }

    public void addDrivingDataListener(DrivingDataListener listener) {
        if (listener != null && !this.drivingDataListeners.contains(listener)) {
            this.drivingDataListeners.add(listener);
        }
    }

    public void removeDrivingDataListener(DrivingDataListener listener) {
        this.drivingDataListeners.remove(listener);
    }

    private void notifyDrivingDataListeners(DrivingData data) {
        for (DrivingDataListener listener : this.drivingDataListeners) {
            try {
                listener.onNewDrivingData(data);
            } catch (Exception e) {
                logger.log(Level.WARNING, "Erro ao notificar listener de DrivingData: " + e.getMessage(), e);
            }
        }
    }

    @Override
    public void run() {
        try {
            // MUDANÇA #2 (Continuação): Chama carregarRotas() AQUI, no início da execução da thread.
            carregarRotas(); 

            if (botPayment != null) {
                botPayment.start();
            }

            // 2. Abre a porta do servidor
            serverSocket = new ServerSocket(serverPort);
            running = true;
            logger.info("MobilityCompany " + companyId + " servidor iniciado e PRONTO na porta " + serverPort);

            // 3. SINALIZA PARA O ENV SIMULATOR QUE ESTÁ 100% PRONTO
            if (this.readyLatch != null) {
                this.readyLatch.countDown();
            }

            // O loop de aceitação de conexões está correto.
            while (running) {
                try {
                    Socket clientSocket = serverSocket.accept();
                    logger.info("MobilityCompany " + companyId + ": Nova conexão de Carro recebida de " + clientSocket.getRemoteSocketAddress());
                    carClientExecutorService.execute(() -> handleCarConnection(clientSocket));
                } catch (IOException e) {
                    if (running) {
                        logger.log(Level.WARNING, "MobilityCompany " + companyId + " erro ao aceitar conexão de Carro: " + e.getMessage());
                    } else {
                        logger.info("MobilityCompany " + companyId + " ServerSocket fechado, parando de aceitar conexões.");
                        break; 
                    }
                }
            }
        } catch (IOException e) {
            logger.log(Level.SEVERE, "MobilityCompany " + companyId + " erro CRÍTICO ao iniciar servidor: " + e.getMessage(), e);
        } finally {
            stopServer(); 
        }
    }
       
    private void handleCarConnection(Socket clientSocket) {
        ObjectInputStream in = null;
        ObjectOutputStream out = null;
        String registeredCarId = null;
        EncriptaDecriptaDES carSessionHandler = null;
        String logPrefix = "COMPANY_HANDLER (" + clientSocket.getRemoteSocketAddress() + "): ";

        try {
            out = new ObjectOutputStream(clientSocket.getOutputStream());
            out.flush(); 
            in = new ObjectInputStream(clientSocket.getInputStream());
            logger.info(logPrefix + "Streams criadas.");

            logger.info(logPrefix + "Aguardando mensagem de registro JSON...");
            String registrationJson = (String) in.readObject();
            logger.info(logPrefix + "Mensagem de registro JSON recebida: " + registrationJson);
            
            Car.CarRegistration carInfo = JsonUtil.fromJson(registrationJson, Car.CarRegistration.class);

            if (carInfo == null || carInfo.getCarId() == null || carInfo.getCarId().trim().isEmpty()) {
                logger.severe(logPrefix + "Falha ao desserializar CarRegistration ou carId nulo. JSON: " + registrationJson);
                throw new IOException("Informações de registro do carro inválidas.");
            }
            registeredCarId = carInfo.getCarId();
            logPrefix = "COMPANY_HANDLER (" + registeredCarId + "): "; 
            logger.info(logPrefix + "Carro " + registeredCarId + " (Driver: " + carInfo.getDriverId() + ") registrando.");

            if (this.rsaHandlerCompany == null) throw new IllegalStateException("rsaHandlerCompany da MobilityCompany é nulo!");
            String rsaPublicKeyBase64 = this.rsaHandlerCompany.getPublicKeyBase64();
            out.writeObject(rsaPublicKeyBase64);
            out.flush();
            logger.info(logPrefix + "Chave pública RSA enviada.");

            logger.info(logPrefix + "Aguardando chave de sessão DES criptografada...");
            byte[] encryptedDesSessionKey = (byte[]) in.readObject();
            logger.info(logPrefix + "Chave de sessão DES criptografada recebida (tamanho: " + (encryptedDesSessionKey != null ? encryptedDesSessionKey.length : "null") + " bytes).");

            byte[] desSessionKeyBytes = this.rsaHandlerCompany.descriptografarComPrivateKey(encryptedDesSessionKey);
            logger.info(logPrefix + "Chave de sessão DES descriptografada.");
            carSessionHandler = new EncriptaDecriptaDES(desSessionKeyBytes);
            
            // Com ConcurrentHashMap, não precisamos mais dos blocos synchronized aqui.
            // As operações .put() e .computeIfAbsent() já são atômicas e thread-safe.
            carSessionEncryptors.put(registeredCarId, carSessionHandler);
            carConnections.put(registeredCarId, clientSocket);
            carDrivingReports.computeIfAbsent(registeredCarId, k -> new ArrayList<>()); // A lista interna não é sync, mas o acesso ao map é.
            
            logger.info(logPrefix + "Chave de sessão DES estabelecida.");

            String confirmationMessage = "REGISTRATION_SECURE_SUCCESS";
            logger.info(logPrefix + "Preparando para enviar confirmação segura: " + confirmationMessage);
            String encryptedConfirmation = carSessionHandler.criptografar(confirmationMessage);
            out.writeObject(encryptedConfirmation);
            out.flush();
            logger.info(logPrefix + "Confirmação segura enviada.");
           
            while (running && clientSocket.isConnected() && !clientSocket.isClosed()) {
                Object receivedObject = in.readObject(); 
                
                if (receivedObject instanceof String) {
                    String encryptedDrivingDataJson = (String) receivedObject;
                    String plainDrivingDataJson = carSessionHandler.descriptografar(encryptedDrivingDataJson);
                    DrivingData drivingData = JsonUtil.fromJson(plainDrivingDataJson, DrivingData.class);
                    
                    if (drivingData != null) {
                        if (!registeredCarId.equals(drivingData.getCarID())) {
                             logger.warning(logPrefix + "ID do carro no DrivingData (" + drivingData.getCarID() + 
                                           ") não corresponde ao ID da sessão (" + registeredCarId + "). Descartando.");
                             continue; 
                        }
                        processDrivingData(registeredCarId, drivingData); 
                        notifyDrivingDataListeners(drivingData);      
                    } else {
                        logger.warning(logPrefix + "Falha ao desserializar DrivingData de " + registeredCarId + ". JSON (descriptografado): " + plainDrivingDataJson);
                    }
                } else {
                    logger.warning(logPrefix + "Recebido tipo de objeto inesperado: " + receivedObject.getClass().getName());
                }
            }
        } catch (java.io.EOFException eofe) {
            logger.warning(logPrefix + "EOFException: " + eofe.getMessage() + ". Cliente provavelmente fechou a conexão.");
        } catch (java.net.SocketException se) {
            logger.info(logPrefix + "SocketException: " + se.getMessage() + " (provavelmente desconexão).");
        } catch (IOException | ClassNotFoundException e) {
            logger.log(Level.WARNING, logPrefix + "IOException ou ClassNotFoundException: " + e.getMessage(), e);
        } catch (Exception e) { 
            logger.log(Level.SEVERE, logPrefix + "Erro GERAL inesperado: " + e.getMessage(), e);
        } finally {
            String finalCarIdForLog = (registeredCarId != null) ? registeredCarId : clientSocket.getRemoteSocketAddress().toString();
            logger.info("COMPANY_HANDLER_FINALLY (" + finalCarIdForLog + "): Encerrando handler.");
            if (registeredCarId != null) {
                // Com ConcurrentHashMap, .remove() também é thread-safe.
                carConnections.remove(registeredCarId);
                carSessionEncryptors.remove(registeredCarId);
            }
            try {
                if (in != null) in.close();
                if (out != null) out.close();
                if (clientSocket != null && !clientSocket.isClosed()) clientSocket.close();
            } catch (IOException e) { 
                 logger.warning("COMPANY_HANDLER_FINALLY (" + finalCarIdForLog + "): Erro ao fechar streams/socket: " + e.getMessage());
            }
        }
    }
    
    // Este método precisa de sincronização porque está iterando sobre o Map, o que não é uma operação única.
    public Map<String, ArrayList<DrivingData>> getConsolidatedCarDrivingReports() {
        synchronized (carDrivingReports) {
            Map<String, ArrayList<DrivingData>> copy = new ConcurrentHashMap<>();
            for (Map.Entry<String, ArrayList<DrivingData>> entry : carDrivingReports.entrySet()) {
                copy.put(entry.getKey(), new ArrayList<>(entry.getValue()));
            }
            return copy;
        }
    }

    private void processDrivingData(String carId, DrivingData drivingData) {
        // Acesso ao Map é seguro, mas a modificação da lista interna precisa de cuidado.
        synchronized(carDrivingReports) {
            ArrayList<DrivingData> carReports = carDrivingReports.get(carId);
            if(carReports != null) {
                carReports.add(drivingData);
                if (carReports.size() > 10000) { 
                    carReports.remove(0); 
                }
            }
        }
    }
    
    public void stopServer() {
        if (!running) {
            return;
        }
        running = false;
        logger.info("MobilityCompany " + companyId + " parando servidor...");

        if (botPayment != null) {
            botPayment.stopBot();
            try {
                botPayment.join(5000);
            } catch (InterruptedException e) {
                logger.warning("Interrupção ao aguardar BotPayment terminar.");
                Thread.currentThread().interrupt();
            }
        }

        // Fecha todos os sockets de cliente ativos.
        for (Socket socket : carConnections.values()) {
            try {
                if (socket != null && !socket.isClosed()) {
                    socket.close();
                }
            } catch (IOException e) {
                logger.log(Level.WARNING, "Erro ao fechar conexão de carro: " + e.getMessage());
            }
        }
        carConnections.clear();
        
        if (carClientExecutorService != null && !carClientExecutorService.isShutdown()) {
            carClientExecutorService.shutdown();
            try {
                if (!carClientExecutorService.awaitTermination(5, java.util.concurrent.TimeUnit.SECONDS)) {
                    carClientExecutorService.shutdownNow();
                }
            } catch (InterruptedException e) {
                carClientExecutorService.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }
        
        if (serverSocket != null && !serverSocket.isClosed()) {
            try {
                serverSocket.close();
                logger.info("MobilityCompany " + companyId + " ServerSocket fechado.");
            } catch (IOException e) {
                logger.log(Level.WARNING, "Erro ao fechar socket do servidor: " + e.getMessage());
            }
        }
        logger.info("MobilityCompany " + companyId + " servidor parado.");
    }
    
    public void addRota(Rota rota) {
        if (rota != null && rota.isOn()) {
            rotasDisponiveis.add(rota);
            logger.info("MobilityCompany " + companyId + " rota adicionada: " + rota.getIdRota());
        }
    }
    
    // MUDANÇA #3: Removido 'synchronized' da assinatura do método para usar locking mais granular.
    public boolean iniciarExecucaoRota(String rotaId, String carId) {
        Rota rotaParaExecutar = null;
        synchronized (rotasDisponiveis) {
            rotaParaExecutar = rotasDisponiveis.stream()
                                .filter(r -> r.getIdRota().equals(rotaId))
                                .findFirst().orElse(null);
            if (rotaParaExecutar != null) {
                rotasDisponiveis.remove(rotaParaExecutar);
            }
        }
        
        if (rotaParaExecutar != null) {
            rotasEmExecucao.add(rotaParaExecutar);
            carRotaMap.put(carId, rotaParaExecutar); // ConcurrentHashMap é seguro
            logger.info("MobilityCompany " + companyId + " rota " + rotaId + " iniciada pelo carro " + carId);
            return true;
        }
        logger.warning("MobilityCompany " + companyId + " rota " + rotaId + " não encontrada para iniciar execução");
        return false;
    }
    
    public boolean finalizarExecucaoRota(String rotaId, String carId) {
        Rota rotaExecutada = null;
        synchronized (rotasEmExecucao) {
             rotaExecutada = rotasEmExecucao.stream()
                                .filter(r -> r.getIdRota().equals(rotaId))
                                .findFirst().orElse(null);
            if (rotaExecutada != null) {
                rotasEmExecucao.remove(rotaExecutada);
            }
        }
        
        if (rotaExecutada != null) {
            rotasExecutadas.add(rotaExecutada);
            carRotaMap.remove(carId); // ConcurrentHashMap é seguro
            logger.info("MobilityCompany " + companyId + " rota " + rotaId + " finalizada pelo carro " + carId);
            return true;
        }
        logger.warning("MobilityCompany " + companyId + " rota " + rotaId + " não encontrada para finalizar execução");
        return false;
    }
    
    // Getters
    public List<Rota> getAvailableRotas() {
        return new ArrayList<>(rotasDisponiveis); // synchronizedList garante que a cópia seja segura
    }
    
    public List<Rota> getRotasEmExecucao() {
        return new ArrayList<>(rotasEmExecucao);
    }
    
    public List<Rota> getRotasExecutadas() {
        return new ArrayList<>(rotasExecutadas);
    }
    
    public Account getCompanyAccount() {
        return companyAccount;
    }
    
    public String getCompanyId() {
        return companyId;
    }

    public BotPayment getBotPayment() {
        return botPayment;
    }
}