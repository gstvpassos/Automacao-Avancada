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

/**
 * Classe que representa uma empresa de mobilidade que gerencia carros e rotas.
 * Atua como um servidor para receber conexões dos carros e processar dados de condução.
 */
public class MobilityCompany extends Thread {
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
    private Map<String, ObjectOutputStream> carOutputStreams;
    // Mapa para armazenar os relatórios de condução de cada carro (ID do carro -> Lista de DrivingData)
    private Map<String, ArrayList<DrivingData>> carDrivingReports;
    // Mapa para associar carros às suas rotas atuais (ID do carro -> Rota)
    private Map<String, Rota> carRotaMap;
    
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
        this.carOutputStreams = new HashMap<>();
        this.carDrivingReports = new HashMap<>();
        this.carRotaMap = new HashMap<>();
        
        // Inicializa o pool de threads para lidar com conexões de carros
        this.carClientExecutorService = Executors.newCachedThreadPool();
        
        // Carrega as rotas do arquivo XML
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
    
    /**
     * Método principal da thread que inicia o servidor e aguarda conexões dos carros.
     */
    @Override
    public void run() {
        try {
            // Inicializa o servidor socket
            serverSocket = new ServerSocket(serverPort);
            running = true;
            System.out.println("[MobilityCompany " + companyId + "] Servidor iniciado na porta " + serverPort);
            
            // Loop principal para aceitar conexões
            while (running) {
                try {
                    // Aguarda uma nova conexão
                    Socket clientSocket = serverSocket.accept();
                    
                    // Processa a conexão em uma thread separada
                    carClientExecutorService.execute(() -> handleCarConnection(clientSocket));
                    
                } catch (IOException e) {
                    if (running) {
                        System.err.println("[MobilityCompany " + companyId + "] Erro ao aceitar conexão: " + e.getMessage());
                    }
                }
            }
        } catch (IOException e) {
            System.err.println("[MobilityCompany " + companyId + "] Erro ao iniciar servidor: " + e.getMessage());
        } finally {
            stopServer();
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
        String carId = null;
        
        try {
            // Configura streams de entrada e saída
            out = new ObjectOutputStream(clientSocket.getOutputStream());
            in = new ObjectInputStream(clientSocket.getInputStream());
            
            // Recebe o ID do carro
            carId = (String) in.readObject();
            System.out.println("[MobilityCompany " + companyId + "] Carro conectado: " + carId);
            
            // Registra a conexão
            synchronized (carConnections) {
                carConnections.put(carId, clientSocket);
                carOutputStreams.put(carId, out);
                carDrivingReports.put(carId, new ArrayList<>());
            }
            
            // Envia confirmação de conexão
            out.writeObject("CONNECTED");
            out.flush();
            
            // Loop para receber dados do carro
            while (running && !clientSocket.isClosed()) {
                Object data = in.readObject();
                
                if (data instanceof DrivingData) {
                    // Processa dados de condução
                    processDrivingData(carId, (DrivingData) data);
                } else if (data instanceof String) {
                    // Processa comandos
                    processCommand(carId, (String) data, out);
                }
            }
            
        } catch (IOException | ClassNotFoundException e) {
            System.err.println("[MobilityCompany " + companyId + "] Erro na conexão com carro " + 
                    (carId != null ? carId : "desconhecido") + ": " + e.getMessage());
        } finally {
            // Limpa recursos
            if (carId != null) {
                synchronized (carConnections) {
                    carConnections.remove(carId);
                    carOutputStreams.remove(carId);
                    // Não removemos os relatórios de condução para manter o histórico
                }
                System.out.println("[MobilityCompany " + companyId + "] Carro desconectado: " + carId);
            }
            
            try {
                if (in != null) in.close();
                if (out != null) out.close();
                if (clientSocket != null && !clientSocket.isClosed()) clientSocket.close();
            } catch (IOException e) {
                System.err.println("[MobilityCompany " + companyId + "] Erro ao fechar conexão: " + e.getMessage());
            }
        }
    }
    
    /**
     * Processa dados de condução recebidos de um carro.
     * 
     * @param carId ID do carro
     * @param drivingData Dados de condução
     */
    private void processDrivingData(String carId, DrivingData drivingData) {
        // Adiciona os dados ao relatório do carro
        synchronized (carDrivingReports) {
            ArrayList<DrivingData> reports = carDrivingReports.get(carId);
            if (reports != null) {
                reports.add(drivingData);
                
                // Log para depuração
                System.out.println("[MobilityCompany " + companyId + "] Dados recebidos do carro " + carId + 
                        ": Velocidade=" + drivingData.getSpeed() + 
                        ", Consumo=" + drivingData.getFuelConsumption());
                
                // Atualiza os dados de condução na rota atual do carro, se houver
                synchronized (carRotaMap) {
                    Rota rotaAtual = carRotaMap.get(carId);
                    if (rotaAtual != null && rotaAtual.getStatus() == Rota.RotaStatus.IN_PROGRESS) {
                        rotaAtual.addDrivingData(drivingData);
                    }
                }
            }
        }
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
    public boolean sendMessageToCar(String carId, Object message) {
        synchronized (carOutputStreams) {
            ObjectOutputStream out = carOutputStreams.get(carId);
            if (out != null) {
                try {
                    out.writeObject(message);
                    out.flush();
                    return true;
                } catch (IOException e) {
                    System.err.println("[MobilityCompany " + companyId + "] Erro ao enviar mensagem para o carro " + carId + ": " + e.getMessage());
                    return false;
                }
            }
        }
        return false;
    }
    
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
            carOutputStreams.clear();
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
