// package io.sim;

// import io.sim.utils.JsonUtil;
// import java.io.IOException;
// import java.io.ObjectInputStream;
// import java.io.ObjectOutputStream;
// import java.net.ServerSocket;
// import java.net.Socket;
// import java.security.PublicKey;
// import java.util.ArrayList;
// import java.util.HashMap;
// import java.util.List;
// import java.util.Map;
// import java.util.concurrent.ExecutorService;
// import java.util.concurrent.Executors;
// import java.util.logging.Level;
// import java.util.logging.Logger;

// public class MobilityCompany extends Thread {
//     private static final Logger logger = Logger.getLogger(MobilityCompany.class.getName());

//     private String companyId;
//     private int serverPort;
//     private ServerSocket serverSocket;
//     private volatile boolean running = false; // Usar volatile
//     private ExecutorService carClientExecutorService;

//     private AlphaBank alphaBankServer;
//     private Account companyAccount;
    
//     private ArrayList<Rota> rotasDisponiveis;
//     private ArrayList<Rota> rotasEmExecucao;
//     private ArrayList<Rota> rotasExecutadas;
//     private String rotasXmlPath;
    
//     private Map<String, Socket> carConnections;
//     private Map<String, ArrayList<DrivingData>> carDrivingReports;
//     private Map<String, Rota> carRotaMap;

//     private EncriptaDecriptaRSA rsaHandlerCompany;
//     private Map<String, EncriptaDecriptaDES> carSessionEncryptors;

//     private List<DrivingDataListener> drivingDataListeners = new ArrayList<>(); // Sincronize o acesso se modificar de múltiplas threads

//     public MobilityCompany(String companyId, int serverPort, AlphaBank alphaBankServer, Account companyAccount, String rotasXmlPath) {
//         this.companyId = companyId;
//         this.serverPort = serverPort;
//         this.alphaBankServer = alphaBankServer;
//         this.companyAccount = companyAccount;
//         this.rotasXmlPath = rotasXmlPath;
        
//         this.rotasDisponiveis = new ArrayList<>();
//         this.rotasEmExecucao = new ArrayList<>();
//         this.rotasExecutadas = new ArrayList<>();
//         this.carConnections = new HashMap<>();
//         this.carDrivingReports = new HashMap<>();
//         this.carRotaMap = new HashMap<>();
//         this.carSessionEncryptors = new HashMap<>();

//         this.carClientExecutorService = Executors.newCachedThreadPool();
        
//         try {
//             this.rsaHandlerCompany = new EncriptaDecriptaRSA();
//             logger.info("MobilityCompany " + companyId + " RSA KeyPair gerado.");
//         } catch (Exception e) {
//             logger.log(Level.SEVERE, "MobilityCompany " + companyId + " falha ao gerar RSA KeyPair.", e);
//             throw new RuntimeException("Falha ao inicializar RSA para MobilityCompany", e);
//         }
        
//         carregarRotas();
//         this.setName("MobilityCompany_ServerThread"); // Nome da thread
//     }
    
//     public interface DrivingDataListener {
//         void onNewDrivingData(DrivingData data);
//     }

//     public synchronized void addDrivingDataListener(DrivingDataListener listener) {
//         if (listener != null && !this.drivingDataListeners.contains(listener)) {
//             this.drivingDataListeners.add(listener);
//         }
//     }

//     public synchronized void removeDrivingDataListener(DrivingDataListener listener) {
//         this.drivingDataListeners.remove(listener);
//     }

//     private synchronized void notifyDrivingDataListeners(DrivingData data) {
//         for (DrivingDataListener listener : this.drivingDataListeners) {
//             try {
//                 listener.onNewDrivingData(data);
//             } catch (Exception e) {
//                 logger.log(Level.WARNING, "MobilityCompany: Erro ao notificar listener de DrivingData: " + e.getMessage(), e);
//             }
//         }
//     }

//     @Override
//     public void run() {
//         try {
//             serverSocket = new ServerSocket(serverPort);
//             running = true;
//             logger.info("[MobilityCompany " + companyId + "] Servidor iniciado na porta " + serverPort);
            
//             while (running) {
//                 try {
//                     Socket clientSocket = serverSocket.accept();
//                     clientSocket.setSoTimeout(20000); // Timeout de leitura (20s) para o handler
//                     logger.info("[MobilityCompany " + companyId + "] Nova conexão de Carro aceita de: " + clientSocket.getRemoteSocketAddress());
//                     carClientExecutorService.execute(() -> handleCarConnection(clientSocket));
//                 } catch (IOException e) {
//                     if (running) { // Só loga como erro se o servidor ainda deveria estar rodando
//                         logger.log(Level.WARNING, "[MobilityCompany " + companyId + "] Erro ao aceitar conexão de Carro: " + e.getMessage());
//                     } else {
//                         logger.info("[MobilityCompany " + companyId + "] Servidor socket fechado, parando de aceitar conexões.");
//                     }
//                 }
//             }
//         } catch (IOException e) {
//             logger.log(Level.SEVERE, "[MobilityCompany " + companyId + "] Erro CRÍTICO ao iniciar o servidor socket: " + e.getMessage(), e);
//         } finally {
//             if (!carClientExecutorService.isShutdown()) {
//                 carClientExecutorService.shutdown();
//             }
//             logger.info("[MobilityCompany " + companyId + "] Servidor principal encerrado.");
//         }
//     }
    
//     private void handleCarConnection(Socket clientSocket) {
//         ObjectInputStream in = null;
//         ObjectOutputStream out = null;
//         String registeredCarId = null;
//         EncriptaDecriptaDES carSessionDecryptor = null; // Renomeado para clareza de propósito

//         try {
//             out = new ObjectOutputStream(clientSocket.getOutputStream());
//             out.flush(); 
//             in = new ObjectInputStream(clientSocket.getInputStream());
//             logger.fine("MobilityCompany: Streams criadas para " + clientSocket.getRemoteSocketAddress());

//             String registrationJson = (String) in.readObject();
//             logger.fine("MobilityCompany: Mensagem de registro JSON recebida: " + registrationJson);
            
//             Car.CarRegistration carInfo = JsonUtil.fromJson(registrationJson, Car.CarRegistration.class);

//             if (carInfo == null || carInfo.getCarId() == null || carInfo.getCarId().trim().isEmpty()) {
//                 throw new IOException("Informações de registro do carro inválidas ou carId nulo/vazio.");
//             }
//             registeredCarId = carInfo.getCarId();
//             logger.info("MobilityCompany: Carro " + registeredCarId + " (Driver: " + carInfo.getDriverId() + ") registrando.");

//             out.writeObject(this.rsaHandlerCompany.getPublicKeyBase64());
//             out.flush();
//             logger.info("MobilityCompany: Chave pública RSA enviada para " + registeredCarId);

//             byte[] encryptedDesSessionKey = (byte[]) in.readObject();
//             byte[] desSessionKeyBytes = this.rsaHandlerCompany.descriptografarComPrivateKey(encryptedDesSessionKey);
//             carSessionDecryptor = new EncriptaDecriptaDES(desSessionKeyBytes);
            
//             synchronized (carSessionEncryptors) {
//                 carSessionEncryptors.put(registeredCarId, carSessionDecryptor);
//             }
//             synchronized (carConnections) {
//                 carConnections.put(registeredCarId, clientSocket);
//             }
//             synchronized (carDrivingReports) {
//                 carDrivingReports.computeIfAbsent(registeredCarId, k -> new ArrayList<>());
//             }
//             logger.info("MobilityCompany: Chave de sessão DES estabelecida com " + registeredCarId);

//             String confirmationMessage = "REGISTRATION_SECURE_SUCCESS";
//             String encryptedConfirmation = carSessionDecryptor.criptografar(confirmationMessage);
//             out.writeObject(encryptedConfirmation);
//             out.flush();
//             logger.info("MobilityCompany: Confirmação segura enviada para " + registeredCarId);

//             // Loop para receber dados de condução criptografados
//             while (running && clientSocket.isConnected() && !clientSocket.isClosed()) {
//                 Object receivedObject = in.readObject(); 
                
//                 if (receivedObject instanceof String) {
//                     String encryptedDrivingDataJson = (String) receivedObject;
                    
//                     String plainDrivingDataJson = carSessionDecryptor.descriptografar(encryptedDrivingDataJson);
//                     DrivingData drivingData = JsonUtil.fromJson(plainDrivingDataJson, DrivingData.class);
                    
//                     if (drivingData != null) {
//                         if (!registeredCarId.equals(drivingData.getAutoID())) { // Usa getAutoID() conforme DrivingData
//                              logger.warning("MobilityCompany: ID do carro no DrivingData (" + drivingData.getAutoID() + 
//                                            ") não corresponde ao ID da sessão (" + registeredCarId + "). Descartando.");
//                              continue; 
//                         }
//                         processDrivingData(registeredCarId, drivingData); 
//                         notifyDrivingDataListeners(drivingData);      
//                     } else {
//                         logger.warning("MobilityCompany: Falha ao desserializar DrivingData de " + registeredCarId + ". JSON recebido (após descriptografia): " + plainDrivingDataJson);
//                     }
//                 } else {
//                     logger.warning("MobilityCompany: Recebido tipo de objeto inesperado de " + registeredCarId + ": " + receivedObject.getClass().getName());
//                 }
//             }
//         } catch (java.io.EOFException eofe) {
//             logger.info("MobilityCompany: Carro " + (registeredCarId != null ? registeredCarId : clientSocket.getRemoteSocketAddress()) + " desconectou (EOF).");
//         } catch (java.net.SocketException se) {
//             logger.info("MobilityCompany: SocketException com carro " + (registeredCarId != null ? registeredCarId : clientSocket.getRemoteSocketAddress()) + ": " + se.getMessage() + " (provavelmente desconexão).");
//         } catch (IOException | ClassNotFoundException e) {
//             if (running) {
//                 logger.log(Level.WARNING, "MobilityCompany: Conexão com carro " + 
//                         (registeredCarId != null ? registeredCarId : clientSocket.getRemoteSocketAddress()) + 
//                         " perdida ou erro: " + e.getMessage());
//             }
//         } catch (Exception e) { // Erros de criptografia etc.
//              logger.log(Level.SEVERE, "MobilityCompany: Erro inesperado no handler do carro " + 
//                         (registeredCarId != null ? registeredCarId : clientSocket.getRemoteSocketAddress()) + 
//                         ": " + e.getMessage(), e);
//         } finally {
//             if (registeredCarId != null) {
//                 synchronized (carConnections) {
//                     carConnections.remove(registeredCarId);
//                 }
//                 synchronized (carSessionEncryptors) {
//                     carSessionEncryptors.remove(registeredCarId);
//                 }
//                 logger.info("MobilityCompany: Carro " + registeredCarId + " desconectado. Recursos da sessão limpos.");
//             }
//             try {
//                 if (in != null) in.close();
//                 if (out != null) out.close();
//                 if (clientSocket != null && !clientSocket.isClosed()) clientSocket.close();
//             } catch (IOException e) { 
//                  logger.warning("MobilityCompany: Erro ao fechar streams/socket para " + (registeredCarId != null ? registeredCarId : "") + ": " + e.getMessage());
//             }
//         }
//     }
    
//     // Este método já existe na sua classe e está correto para adicionar aos relatórios.
//     // Apenas adicionei o logger.fine para melhor visualização
//     private void processDrivingData(String carId, DrivingData drivingData) {
//         synchronized (carDrivingReports) {
//             ArrayList<DrivingData> reports = carDrivingReports.get(carId);
//             if (reports == null) { // Deve ser inicializado no handshake
//                 reports = new ArrayList<>();
//                 carDrivingReports.put(carId, reports);
//             }
//             reports.add(drivingData);
            
//             logger.fine("[MobilityCompany " + companyId + "] Dados recebidos e armazenados do carro " + carId + 
//                         ": Velocidade=" + drivingData.getSpeed() + 
//                         ", Timestamp=" + drivingData.getTimeStamp());
//         }
//         // A notificação aos listeners foi movida para handleCarConnection após a chamada deste método.
//     }
    
//     public Map<String, ArrayList<DrivingData>> getConsolidatedCarDrivingReports() {
//         synchronized (carDrivingReports) {
//             Map<String, ArrayList<DrivingData>> defensiveCopy = new HashMap<>();
//             for (Map.Entry<String, ArrayList<DrivingData>> entry : carDrivingReports.entrySet()) {
//                 defensiveCopy.put(entry.getKey(), new ArrayList<>(entry.getValue()));
//             }
//             return defensiveCopy;
//         }
//     }

//     // ... (carregarRotas, processCommand, assignRotaToCar, e outros métodos existentes da MobilityCompany)
//     // Lembre-se que o método carregarRotas atual usa um array fixo de carIds, ajuste-o para sua necessidade.
//     private void carregarRotas() {
//         // Sua lógica existente...
//         // Exemplo:
//         // String[] carIds = {"CAR001", "CAR002", ...}; // Popule isso dinamicamente ou do XML
//         // for (String carIdIter : carIds) {
//         //     Rota rota = new Rota(rotasXmlPath, "RotaPara_" + carIdIter); // Use um ID de rota apropriado
//         //     if (rota.isOn()) {
//         //         rotasDisponiveis.add(rota);
//         //     }
//         // }
//         logger.info("[MobilityCompany " + companyId + "] Carregamento de rotas concluído (lógica de exemplo). Rotas disponíveis: " + rotasDisponiveis.size());
//     }

//     public List<Rota> getAvailableRotas() {
//         synchronized (rotasDisponiveis) {
//             return new ArrayList<>(rotasDisponiveis);
//         }
//     }
    
//     // Adicione outros getters e setters que você tinha, se necessário
//     public void stopServer() { // Certifique-se que este método é chamado ao encerrar EnvSimulator
//         running = false;
//         try {
//             if (serverSocket != null && !serverSocket.isClosed()) {
//                 serverSocket.close(); // Isso fará com que o loop de accept() lance uma exceção e saia
//             }
//         } catch (IOException e) {
//             logger.log(Level.WARNING, "[MobilityCompany " + companyId + "] Erro ao fechar server socket principal: " + e.getMessage(), e);
//         }

//         if (carClientExecutorService != null) {
//             carClientExecutorService.shutdown();
//             try {
//                 if (!carClientExecutorService.awaitTermination(5, TimeUnit.SECONDS)) {
//                     carClientExecutorService.shutdownNow();
//                 }
//             } catch (InterruptedException e) {
//                 carClientExecutorService.shutdownNow();
//                 Thread.currentThread().interrupt();
//             }
//         }
//         logger.info("[MobilityCompany " + companyId + "] Servidor e pool de threads encerrados.");
//     }
// }
