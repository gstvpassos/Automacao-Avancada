package io.sim;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.logging.Level; 
import java.util.logging.Logger;
import sim.traci4j.src.java.it.polito.appeal.traci.protocol.Constants;

import io.sim.reporting.ExcelReportGenerator.DrivingDataListener;
import io.sim.utils.JsonUtil;

/**
 * Classe que representa uma empresa de mobilidade que gerencia carros e rotas.
 * Atua como um servidor para receber conexões dos carros e processar dados de condução.
 */
public class MobilityCompany extends Thread {
    private static final Logger logger = Logger.getLogger(MobilityCompany.class.getName()); 

    private String companyId;
    private int serverPort; // Porta para escutar conexões dos Carros
    private ServerSocket serverSocket;
    private boolean running = false;
    private ExecutorService carClientExecutorService;

    private AlphaBank alphaBankServer; // Referência ao servidor AlphaBank
    private Account companyAccount;    // Conta da MobilityCompany no AlphaBank
    
    // Coleções para gerenciamento de rotas
    private ArrayList<Rota> rotasDisponiveis;
    private ArrayList<Rota> rotasEmExecucao;
    private ArrayList<Rota> rotasExecutadas;
    
    // Caminho para o arquivo XML de rotas
    private String rotasXmlPath;
    
    // Mapa para armazenar os carros conectados (ID do carro -> Socket)
    private Map<String, Socket> carConnections;
    // Mapa para armazenar os streams de saída para cada carro (ID do carro -> ObjectOutputStream)
    //private Map<String, ObjectOutputStream> carOutputStreams;
    // Mapa para armazenar os relatórios de condução de cada carro (ID do carro -> Lista de DrivingData)
    private Map<String, ArrayList<DrivingData>> carDrivingReports;
    // Mapa para associar carros às suas rotas atuais (ID do carro -> Rota)
    private Map<String, Rota> carRotaMap;
    
    // NOVOS Atributos para criptografia
    private EncriptaDecriptaRSA rsaHandlerCompany; // Renomeado para evitar conflito se houver outro rsaHandler
    private Map<String, EncriptaDecriptaDES> carSessionEncryptors;

    // Para notificar listeners de gráficos em tempo real
    private List<DrivingDataListener> drivingDataListeners = new ArrayList<>();

    /**
     * Construtor da classe MobilityCompany.
     * 
     * @param companyId ID da empresa
     * @param serverPort Porta do servidor para conexões dos carros
     * @param alphaBankServer Referência ao servidor AlphaBank
     * @param companyAccount Conta da empresa no AlphaBank
     * @param rotasXmlPath Caminho para o arquivo XML de rotas
     */
    public MobilityCompany(String companyId, int serverPort, AlphaBank alphaBankServer, Account companyAccount, String rotasXmlPath) {
        this.companyId = companyId;
        this.serverPort = serverPort;
        this.alphaBankServer = alphaBankServer;
        this.companyAccount = companyAccount;
        this.rotasXmlPath = rotasXmlPath;
        
        this.rotasDisponiveis = new ArrayList<>();
        this.rotasEmExecucao = new ArrayList<>();
        this.rotasExecutadas = new ArrayList<>();
        this.carConnections = new HashMap<>();
        this.carDrivingReports = new HashMap<>(); // Onde os dados são acumulados
        this.carRotaMap = new HashMap<>();
        this.carSessionEncryptors = new HashMap<>();

        this.carClientExecutorService = Executors.newCachedThreadPool();
        
        try {
            this.rsaHandlerCompany = new EncriptaDecriptaRSA();
            logger.info("MobilityCompany " + companyId + " RSA KeyPair gerado.");
        } catch (Exception e) {
            logger.log(Level.SEVERE, "MobilityCompany " + companyId + " falha ao gerar RSA KeyPair.", e);
            throw new RuntimeException("Falha ao inicializar RSA para MobilityCompany", e);
        }
        
        carregarRotas();
    }
    
    /**
     * Carrega as rotas do arquivo XML.
     */
    private void carregarRotas() {
        try {
            // Aqui você pode implementar a lógica para carregar todas as rotas disponíveis
            // do arquivo XML. Por exemplo, você pode ler o arquivo XML, identificar todos os
            // veículos e criar uma Rota para cada um.
            
            System.out.println("[MobilityCompany " + companyId + "] Carregando rotas do arquivo: " + rotasXmlPath);
            
            // Exemplo: Carregar rotas para veículos CAR1, CAR2, etc.
            // Na prática, você deve ler o arquivo XML e extrair os IDs dos veículos
            String[] carIds = {"CAR1", "CAR2", "CAR3", "CAR4", "CAR5"};
            
            for (String carId : carIds) {
                Rota rota = new Rota(rotasXmlPath, carId);
                if (rota.isOn()) {
                    rotasDisponiveis.add(rota);
                    System.out.println("[MobilityCompany " + companyId + "] Rota carregada para: " + carId);
                } else {
                    System.err.println("[MobilityCompany " + companyId + "] Falha ao carregar rota para: " + carId);
                }
            }
            
            System.out.println("[MobilityCompany " + companyId + "] Total de rotas carregadas: " + rotasDisponiveis.size());
            
        } catch (Exception e) {
            System.err.println("[MobilityCompany " + companyId + "] Erro ao carregar rotas: " + e.getMessage());
            e.printStackTrace();
        }
    }
    
    // Interface para listener de DrivingData (para gráficos em tempo real)
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

    /**
     * Método principal da thread que inicia o servidor e aguarda conexões dos carros.
     */
    @Override
    public void run() {
        try {
            serverSocket = new ServerSocket(serverPort);
            running = true;
            logger.info("[MobilityCompany " + companyId + "] Servidor iniciado na porta " + serverPort);
            
            while (running) {
                try {
                    Socket clientSocket = serverSocket.accept();
                    carClientExecutorService.execute(() -> handleCarConnection(clientSocket));
                } catch (IOException e) {
                    if (running) {
                        logger.log(Level.WARNING, "[MobilityCompany " + companyId + "] Erro ao aceitar conexão de Carro: " + e.getMessage());
                    }
                }
            }
        } catch (IOException e) {
            logger.log(Level.SEVERE, "[MobilityCompany " + companyId + "] Erro CRÍTICO ao iniciar servidor: " + e.getMessage(), e);
        } finally {
            stopServer(); // Garante que o servidor seja parado
        }
    }
       
    /**
     * Manipula uma conexão de carro.
     * 
     * @param clientSocket Socket do cliente conectado
     */
    private void handleCarConnection(Socket clientSocket) {
        ObjectInputStream in = null;
        ObjectOutputStream out = null;
        String registeredCarId = null;
        EncriptaDecriptaDES carSessionDecryptor = null;
        String logPrefix = "COMPANY_HANDLER (" + clientSocket.getRemoteSocketAddress() + "): ";

        try {
            out = new ObjectOutputStream(clientSocket.getOutputStream());
            out.flush(); 
            in = new ObjectInputStream(clientSocket.getInputStream());
            logger.info(logPrefix + "Streams criadas.");

            // 1. RECEBER REGISTRO JSON DO CARRO
            logger.info(logPrefix + "Aguardando mensagem de registro JSON...");
            String registrationJson = (String) in.readObject();
            logger.info(logPrefix + "Mensagem de registro JSON recebida: " + registrationJson);
            
            Car.CarRegistration carInfo = JsonUtil.fromJson(registrationJson, Car.CarRegistration.class);

            if (carInfo == null || carInfo.getCarId() == null || carInfo.getCarId().trim().isEmpty()) {
                logger.severe(logPrefix + "Falha ao desserializar CarRegistration ou carId nulo. JSON: " + registrationJson);
                throw new IOException("Informações de registro do carro inválidas.");
            }
            registeredCarId = carInfo.getCarId();
            logPrefix = "COMPANY_HANDLER (" + registeredCarId + "): "; // Atualiza prefixo com ID do carro
            logger.info(logPrefix + "Carro " + registeredCarId + " (Driver: " + carInfo.getDriverId() + ") registrando.");

            // 2. ENVIAR CHAVE PÚBLICA RSA DA COMPANY
            logger.info(logPrefix + "Preparando para enviar chave pública RSA...");
            if (this.rsaHandlerCompany == null) throw new IllegalStateException("rsaHandlerCompany da MobilityCompany é nulo!");
            String rsaPublicKeyBase64 = this.rsaHandlerCompany.getPublicKeyBase64();
            out.writeObject(rsaPublicKeyBase64);
            out.flush();
            logger.info(logPrefix + "Chave pública RSA enviada.");

            // 3. RECEBER E DESCRIPTOGRAFAR CHAVE DE SESSÃO DES
            logger.info(logPrefix + "Aguardando chave de sessão DES criptografada...");
            byte[] encryptedDesSessionKey = (byte[]) in.readObject();
            logger.info(logPrefix + "Chave de sessão DES criptografada recebida (tamanho: " + (encryptedDesSessionKey != null ? encryptedDesSessionKey.length : "null") + " bytes).");

            byte[] desSessionKeyBytes = this.rsaHandlerCompany.descriptografarComPrivateKey(encryptedDesSessionKey);
            logger.info(logPrefix + "Chave de sessão DES descriptografada.");
            carSessionDecryptor = new EncriptaDecriptaDES(desSessionKeyBytes);
            
            synchronized (carSessionEncryptors) {
                carSessionEncryptors.put(registeredCarId, carSessionDecryptor);
            }
            synchronized (carConnections) {
                carConnections.put(registeredCarId, clientSocket);
            }
            synchronized (carDrivingReports) {
                carDrivingReports.computeIfAbsent(registeredCarId, k -> new ArrayList<>());
            }
            logger.info(logPrefix + "Chave de sessão DES estabelecida.");

            // 4. ENVIAR CONFIRMAÇÃO SEGURA
            String confirmationMessage = "REGISTRATION_SECURE_SUCCESS";
            logger.info(logPrefix + "Preparando para enviar confirmação segura: " + confirmationMessage);
            String encryptedConfirmation = carSessionDecryptor.criptografar(confirmationMessage);
            out.writeObject(encryptedConfirmation);
            out.flush();
            logger.info(logPrefix + "Confirmação segura enviada.");
           
            // Loop para receber dados de condução criptografados
            while (running && clientSocket.isConnected() && !clientSocket.isClosed()) {
                //logger.fine(logPrefix + "Aguardando próximo objeto...");
                Object receivedObject = in.readObject(); 
                
                if (receivedObject instanceof String) {
                    String encryptedDrivingDataJson = (String) receivedObject;
                    //logger.fine(logPrefix + "String Criptografada recebida (início): " + encryptedDrivingDataJson.substring(0, Math.min(encryptedDrivingDataJson.length(), 30)) + "...");
                    
                    String plainDrivingDataJson = carSessionDecryptor.descriptografar(encryptedDrivingDataJson);
                    //logger.fine(logPrefix + "JSON Descriptografado: " + plainDrivingDataJson);
                    
                    DrivingData drivingData = JsonUtil.fromJson(plainDrivingDataJson, DrivingData.class);
                    
                    if (drivingData != null) {
                        if (!registeredCarId.equals(drivingData.getAutoID())) {
                             logger.warning(logPrefix + "ID do carro no DrivingData (" + drivingData.getAutoID() + 
                                           ") não corresponde ao ID da sessão (" + registeredCarId + "). Descartando.");
                             continue; 
                        }
                        //logger.fine(logPrefix + "DrivingData desserializado (Timestamp: " + drivingData.getTimeStamp() + "). Chamando processDrivingData...");
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
            logger.warning(logPrefix + "EOFException durante o handshake: " + eofe.getMessage() + ". Cliente provavelmente fechou a conexão prematuramente ou não enviou o objeto esperado.");
        } catch (java.net.SocketException se) {
            logger.info(logPrefix + "SocketException: " + se.getMessage() + " (provavelmente desconexão).");
        } catch (IOException | ClassNotFoundException e) {
            logger.log(Level.SEVERE, logPrefix + "IOException ou ClassNotFoundException durante handshake: " + e.getMessage(), e);
            if (running) {
                logger.log(Level.WARNING, logPrefix + "Conexão perdida ou erro: " + e.getMessage());
            }
        } catch (Exception e) { 
            logger.log(Level.SEVERE, logPrefix + "Erro GERAL inesperado durante handshake: " + e.getMessage(), e);
        } finally {
            String finalCarId = (registeredCarId != null) ? registeredCarId : "ClienteDesconhecido@" + clientSocket.getRemoteSocketAddress();
            logger.info("COMPANY_HANDLER_FINALLY (" + finalCarId + "): Encerrando handler.");
            String finalCarIdForLog = (registeredCarId != null) ? registeredCarId : clientSocket.getRemoteSocketAddress().toString();
            logger.info("COMPANY_HANDLER (" + finalCarIdForLog + "): Bloco finally do handshake alcançado.");
            if (registeredCarId != null) {
                synchronized (carConnections) {
                    carConnections.remove(registeredCarId);
                }
                synchronized (carSessionEncryptors) {
                    carSessionEncryptors.remove(registeredCarId);
                }
                // Não removemos os carDrivingReports para manter o histórico para relatórios finais
            }
            try {
                if (in != null) in.close();
                if (out != null) out.close();
                if (clientSocket != null && !clientSocket.isClosed()) clientSocket.close();
            } catch (IOException e) { 
                 logger.warning("COMPANY_HANDLER_FINALLY (" + finalCarId + "): Erro ao fechar streams/socket: " + e.getMessage());
            }
        }
    }
    
    public Map<String, ArrayList<DrivingData>> getConsolidatedCarDrivingReports() {
        synchronized (carDrivingReports) {
            // Retorna uma cópia profunda para garantir thread-safety e evitar modificação externa
            Map<String, ArrayList<DrivingData>> defensiveCopy = new HashMap<>();
            for (Map.Entry<String, ArrayList<DrivingData>> entry : carDrivingReports.entrySet()) {
                defensiveCopy.put(entry.getKey(), new ArrayList<>(entry.getValue()));
            }
            return defensiveCopy;
        }
    }

    /**
     * Processa dados de condução recebidos de um carro.
     * 
     * @param carId ID do carro
     * @param drivingData Dados de condução
     */
    private void processDrivingData(String carId, DrivingData drivingData) {
        String logPrefix = "COMPANY_PROCESS_DATA (" + carId + "): ";
        synchronized (carDrivingReports) {
            ArrayList<DrivingData> reports = carDrivingReports.computeIfAbsent(carId, k -> {
                logger.info(logPrefix + "Criando nova lista de relatórios para este carro.");
                return new ArrayList<>();
            });
            reports.add(drivingData);
            logger.info(logPrefix + "Dados ARMAZENADOS. Total para este carro: " + reports.size() + ". Timestamp: " + drivingData.getTimeStamp());
        }
        // A notificação aos listeners agora é feita em handleCarConnection após esta chamada.
    }
    
    /**
     * Processa comandos recebidos de um carro.
     * 
     * @param carId ID do carro
     * @param command Comando recebido
     * @param out Stream de saída para responder
     */
    private void processCommand(String carId, String command, ObjectOutputStream out) throws IOException {
        System.out.println("[MobilityCompany " + companyId + "] Comando recebido do carro " + carId + ": " + command);
        
        // Processa diferentes tipos de comandos
        if (command.startsWith("REQUEST_ROTA")) {
            // Lógica para atribuir uma rota ao carro
            Rota rota = assignRotaToCar(carId);
            if (rota != null) {
                out.writeObject("ROTA_ASSIGNED");
                out.writeObject(rota);
            } else {
                out.writeObject("NO_ROTA_AVAILABLE");
            }
            out.flush();
        } else if (command.startsWith("ROTA_COMPLETED")) {
            // Lógica para processar conclusão de rota
            processRotaCompletion(carId);
            out.writeObject("ROTA_COMPLETION_ACKNOWLEDGED");
            out.flush();
        } else if (command.startsWith("REQUEST_PAYMENT")) {
            // Lógica para processar solicitação de pagamento
            double amount = processPaymentRequest(carId);
            out.writeObject("PAYMENT_PROCESSED");
            out.writeObject(amount);
            out.flush();
        } else if (command.startsWith("START_ROTA")) {
            // Lógica para iniciar uma rota
            boolean success = startRotaForCar(carId);
            out.writeObject(success ? "ROTA_STARTED" : "ROTA_START_FAILED");
            out.flush();
        } else if (command.startsWith("CANCEL_ROTA")) {
            // Lógica para cancelar uma rota
            boolean success = cancelRotaForCar(carId);
            out.writeObject(success ? "ROTA_CANCELLED" : "ROTA_CANCEL_FAILED");
            out.flush();
        } else if (command.startsWith("GET_ROTA_STATUS")) {
            // Lógica para obter o status da rota atual
            Rota.RotaStatus status = getRotaStatusForCar(carId);
            out.writeObject("ROTA_STATUS");
            out.writeObject(status);
            out.flush();
        }
    }
    
    /**
     * Atribui uma rota a um carro.
     * 
     * @param carId ID do carro
     * @return Rota atribuída ou null se nenhuma rota estiver disponível
     */
    public Rota assignRotaToCar(String carId) {
        synchronized (rotasDisponiveis) {
            if (rotasDisponiveis.isEmpty()) {
                System.out.println("[MobilityCompany " + companyId + "] Não há rotas disponíveis para atribuir ao carro " + carId);
                return null;
            }
            
            // Obtém a primeira rota disponível
            Rota rota = rotasDisponiveis.remove(0);
            
            // Atribui a rota ao carro
            if (rota.assignRota(carId, carId)) {
                synchronized (rotasEmExecucao) {
                    rotasEmExecucao.add(rota);
                }
                
                synchronized (carRotaMap) {
                    carRotaMap.put(carId, rota);
                }
                
                System.out.println("[MobilityCompany " + companyId + "] Rota " + rota.getIdRota() + " atribuída ao carro " + carId);
                return rota;
            } else {
                // Se a atribuição falhar, devolve a rota para a lista de disponíveis
                rotasDisponiveis.add(rota);
                System.err.println("[MobilityCompany " + companyId + "] Falha ao atribuir rota " + rota.getIdRota() + " ao carro " + carId);
                return null;
            }
        }
    }
    
    /**
     * Inicia a execução de uma rota para um carro.
     * 
     * @param carId ID do carro
     * @return true se o início foi bem-sucedido, false caso contrário
     */
    private boolean startRotaForCar(String carId) {
        synchronized (carRotaMap) {
            Rota rota = carRotaMap.get(carId);
            if (rota != null) {
                boolean success = rota.startRota();
                if (success) {
                    System.out.println("[MobilityCompany " + companyId + "] Rota " + rota.getIdRota() + " iniciada pelo carro " + carId);
                } else {
                    System.err.println("[MobilityCompany " + companyId + "] Falha ao iniciar rota " + rota.getIdRota() + " pelo carro " + carId);
                }
                return success;
            }
        }
        return false;
    }
    
    /**
     * Cancela a execução de uma rota para um carro.
     * 
     * @param carId ID do carro
     * @return true se o cancelamento foi bem-sucedido, false caso contrário
     */
    private boolean cancelRotaForCar(String carId) {
        synchronized (carRotaMap) {
            Rota rota = carRotaMap.get(carId);
            if (rota != null) {
                boolean success = rota.cancelRota();
                if (success) {
                    System.out.println("[MobilityCompany " + companyId + "] Rota " + rota.getIdRota() + " cancelada pelo carro " + carId);
                    
                    // Move a rota para a lista de executadas
                    synchronized (rotasEmExecucao) {
                        rotasEmExecucao.remove(rota);
                    }
                    synchronized (rotasExecutadas) {
                        rotasExecutadas.add(rota);
                    }
                    
                    // Remove a associação do carro com a rota
                    carRotaMap.remove(carId);
                } else {
                    System.err.println("[MobilityCompany " + companyId + "] Falha ao cancelar rota " + rota.getIdRota() + " pelo carro " + carId);
                }
                return success;
            }
        }
        return false;
    }
    
    /**
     * Obtém o status da rota atual de um carro.
     * 
     * @param carId ID do carro
     * @return Status da rota ou null se o carro não tiver uma rota atribuída
     */
    private Rota.RotaStatus getRotaStatusForCar(String carId) {
        synchronized (carRotaMap) {
            Rota rota = carRotaMap.get(carId);
            if (rota != null) {
                return rota.getStatus();
            }
        }
        return null;
    }
    
    /**
     * Processa a conclusão de uma rota por um carro.
     * 
     * @param carId ID do carro
     */
    private void processRotaCompletion(String carId) {
        synchronized (carRotaMap) {
            Rota rota = carRotaMap.get(carId);
            if (rota != null) {
                if (rota.completeRota()) {
                    System.out.println("[MobilityCompany " + companyId + "] Rota " + rota.getIdRota() + " completada pelo carro " + carId);
                    
                    // Move a rota para a lista de executadas
                    synchronized (rotasEmExecucao) {
                        rotasEmExecucao.remove(rota);
                    }
                    synchronized (rotasExecutadas) {
                        rotasExecutadas.add(rota);
                    }
                    
                    // Remove a associação do carro com a rota
                    carRotaMap.remove(carId);
                    
                    // Gera um relatório da rota
                    String summary = rota.generateSummary();
                    System.out.println("[MobilityCompany " + companyId + "] Resumo da rota completada:\n" + summary);
                    
                    // Aqui você poderia implementar lógica para calcular pagamentos,
                    // atualizar estatísticas, etc.
                } else {
                    System.err.println("[MobilityCompany " + companyId + "] Falha ao completar rota " + rota.getIdRota() + " pelo carro " + carId);
                }
            } else {
                System.err.println("[MobilityCompany " + companyId + "] Carro " + carId + " não tem uma rota atribuída para completar");
            }
        }
    }
    
    /**
     * Processa uma solicitação de pagamento de um carro.
     * 
     * @param carId ID do carro
     * @return Valor processado
     */
    private double processPaymentRequest(String carId) {
        // Implementação melhorada - calcula o valor com base nos dados de condução
        double amount = 0.0;
        
        synchronized (carDrivingReports) {
            ArrayList<DrivingData> reports = carDrivingReports.get(carId);
            if (reports != null && !reports.isEmpty()) {
                // Calcula o valor com base na distância percorrida
                DrivingData lastReport = reports.get(reports.size() - 1);
                double distance = lastReport.getOdometer();
                
                // Exemplo: R$ 2,50 por km
                amount = distance * 0.0025; // Converte metros para km e multiplica por 2,50
                
                // Adiciona taxa base
                amount += 5.0;
                
                // Arredonda para 2 casas decimais
                amount = Math.round(amount * 100.0) / 100.0;
            } else {
                // Valor padrão se não houver dados de condução
                amount = 10.0;
            }
        }
        
        System.out.println("[MobilityCompany " + companyId + "] Processando pagamento de R$ " + amount + " para o carro " + carId);
        
        // Aqui você poderia integrar com o AlphaBank para realizar a transferência
        // Por exemplo:
        // alphaBankServer.transferFunds(carId, companyAccount.getAccountId(), amount);
        
        return amount;
    }
    
    /**
     * Envia uma mensagem para um carro específico.
     * 
     * @param carId ID do carro
     * @param message Mensagem a ser enviada
     * @return true se a mensagem foi enviada com sucesso, false caso contrário
     */
    // public boolean sendMessageToCar(String carId, Object message) {
    //     synchronized (carOutputStreams) {
    //         ObjectOutputStream out = carOutputStreams.get(carId);
    //         if (out != null) {
    //             try {
    //                 out.writeObject(message);
    //                 out.flush();
    //                 return true;
    //             } catch (IOException e) {
    //                 System.err.println("[MobilityCompany " + companyId + "] Erro ao enviar mensagem para o carro " + carId + ": " + e.getMessage());
    //                 return false;
    //             }
    //         }
    //     }
    //     return false;
    // }
    
    /**
     * Adiciona uma nova rota para execução.
     * 
     * @param rota Rota a ser adicionada
     */
    public void addRota(Rota rota) {
        if (rota.getStatus() == Rota.RotaStatus.CREATED) {
            synchronized (rotasDisponiveis) {
                rotasDisponiveis.add(rota);
            }
            System.out.println("[MobilityCompany " + companyId + "] Nova rota adicionada: " + rota.getIdRota());
        } else if (rota.getStatus() == Rota.RotaStatus.ASSIGNED || rota.getStatus() == Rota.RotaStatus.IN_PROGRESS) {
            synchronized (rotasEmExecucao) {
                rotasEmExecucao.add(rota);
            }
            System.out.println("[MobilityCompany " + companyId + "] Rota em execução adicionada: " + rota.getIdRota());
        } else {
            synchronized (rotasExecutadas) {
                rotasExecutadas.add(rota);
            }
            System.out.println("[MobilityCompany " + companyId + "] Rota executada adicionada: " + rota.getIdRota());
        }
    }
    
    /**
     * Marca uma rota como executada.
     * 
     * @param rota Rota executada
     */
    public void markRotaAsExecuted(Rota rota) {
        synchronized (rotasEmExecucao) {
            rotasEmExecucao.remove(rota);
        }
        synchronized (rotasExecutadas) {
            rotasExecutadas.add(rota);
        }
        System.out.println("[MobilityCompany " + companyId + "] Rota marcada como executada: " + rota.getIdRota());
    }
    
    /**
     * Obtém os relatórios de condução de um carro específico.
     * 
     * @param carId ID do carro
     * @return Lista de relatórios de condução ou null se o carro não existir
     */
    public ArrayList<DrivingData> getCarDrivingReports(String carId) {
        synchronized (carDrivingReports) {
            return carDrivingReports.get(carId);
        }
    }
    
    /**
     * Obtém a rota atual de um carro.
     * 
     * @param carId ID do carro
     * @return Rota atual do carro ou null se o carro não tiver uma rota atribuída
     */
    public Rota getCurrentRotaForCar(String carId) {
        synchronized (carRotaMap) {
            return carRotaMap.get(carId);
        }
    }
    
    /**
     * Obtém todas as rotas disponíveis.
     * 
     * @return Lista de rotas disponíveis
     */
    public List<Rota> getAvailableRotas() {
        synchronized (rotasDisponiveis) {
            return new ArrayList<>(rotasDisponiveis);
        }
    }
    
    /**
     * Obtém todas as rotas em execução.
     * 
     * @return Lista de rotas em execução
     */
    public List<Rota> getRotasEmExecucao() {
        synchronized (rotasEmExecucao) {
            return new ArrayList<>(rotasEmExecucao);
        }
    }
    
    /**
     * Obtém todas as rotas executadas.
     * 
     * @return Lista de rotas executadas
     */
    public List<Rota> getRotasExecutadas() {
        synchronized (rotasExecutadas) {
            return new ArrayList<>(rotasExecutadas);
        }
    }
    
    /**
     * Obtém todos os carros conectados.
     * 
     * @return Lista de IDs de carros conectados
     */
    public List<String> getConnectedCars() {
        synchronized (carConnections) {
            return new ArrayList<>(carConnections.keySet());
        }
    }
    
    /**
     * Para o servidor e libera recursos.
     */
    public void stopServer() {
        running = false;
        
        // Fecha todas as conexões de carros
        synchronized (carConnections) {
            for (Socket socket : carConnections.values()) {
                try {
                    if (socket != null && !socket.isClosed()) {
                        socket.close();
                    }
                } catch (IOException e) {
                    System.err.println("[MobilityCompany " + companyId + "] Erro ao fechar conexão: " + e.getMessage());
                }
            }
            carConnections.clear();
            //carOutputStreams.clear();
        }
        
        // Fecha o servidor socket
        if (serverSocket != null && !serverSocket.isClosed()) {
            try {
                serverSocket.close();
            } catch (IOException e) {
                System.err.println("[MobilityCompany " + companyId + "] Erro ao fechar servidor socket: " + e.getMessage());
            }
        }
        
        // Desliga o executor service
        if (carClientExecutorService != null) {
            carClientExecutorService.shutdown();
        }
        
        System.out.println("[MobilityCompany " + companyId + "] Servidor parado");
    }
    
    /**
     * Verifica se o servidor está em execução.
     * 
     * @return true se o servidor estiver em execução, false caso contrário
     */
    public boolean isRunning() {
        return running;
    }
    
    /**
     * Obtém o ID da empresa.
     * 
     * @return ID da empresa
     */
    public String getCompanyId() {
        return companyId;
    }
    
    /**
     * Obtém a porta do servidor.
     * 
     * @return Porta do servidor
     */
    public int getServerPort() {
        return serverPort;
    }
    
    /**
     * Obtém a conta da empresa.
     * 
     * @return Conta da empresa
     */
    public Account getCompanyAccount() {
        return companyAccount;
    }
    
    /**
     * Define a conta da empresa.
     * 
     * @param companyAccount Nova conta da empresa
     */
    public void setCompanyAccount(Account companyAccount) {
        this.companyAccount = companyAccount;
    }
    
    /**
     * Obtém a referência ao servidor AlphaBank.
     * 
     * @return Servidor AlphaBank
     */
    public AlphaBank getAlphaBankServer() {
        return alphaBankServer;
    }
    
    /**
     * Define a referência ao servidor AlphaBank.
     * 
     * @param alphaBankServer Novo servidor AlphaBank
     */
    public void setAlphaBankServer(AlphaBank alphaBankServer) {
        this.alphaBankServer = alphaBankServer;
    }
    
    /**
     * Define o ID da empresa.
     * 
     * @param companyId Novo ID da empresa
     */
    public void setCompanyId(String companyId) {
        this.companyId = companyId;
    }

    /**
     * Define a porta do servidor.
     * 
     * @param serverPort Nova porta do servidor
     */
    public void setServerPort(int serverPort) {
        this.serverPort = serverPort;
    }
    
    /**
     * Obtém o caminho do arquivo XML de rotas.
     * 
     * @return Caminho do arquivo XML de rotas
     */
    public String getRotasXmlPath() {
        return rotasXmlPath;
    }
    
    /**
     * Define o caminho do arquivo XML de rotas.
     * 
     * @param rotasXmlPath Novo caminho do arquivo XML de rotas
     */
    public void setRotasXmlPath(String rotasXmlPath) {
        this.rotasXmlPath = rotasXmlPath;
    }
}
