package io.sim;

import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.Serializable;
import java.net.Socket;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Classe que representa um posto de combustível no sistema de simulação.
 * Responsável pelo abastecimento dos veículos e integração com o sistema bancário.
 */
public class FuelStation extends Thread {
    
    private static final Logger logger = Logger.getLogger(FuelStation.class.getName());
    
    // Identificação do posto
    private final String stationId;
    private final String name;
    
    // Conta bancária do posto
    private Account account;
    
    // Controle de execução
    private AtomicBoolean running = new AtomicBoolean(false);
    
    // Preços dos combustíveis
    private double dieselPrice;    // Preço do diesel por litro
    private double gasolinePrice;  // Preço da gasolina por litro
    private double ethanolPrice;   // Preço do etanol por litro
    
    // Conexão com o AlphaBank
    private String bankHost;
    private int bankPort;
    private Socket bankSocket;
    private ObjectOutputStream bankOut;
    private ObjectInputStream bankIn;
    private boolean connected;
    private String login;
    private String senha;
    
    // Controle de bombas de combustível (limite de 2 bombas)
    private final Semaphore pumpSemaphore;
    
    // Fila de carros aguardando abastecimento
    private final Queue<RefuelRequest> waitingCars;
    
    // Objeto de sincronização para notificação
    private final Object refuelLock = new Object();
    
    // Bot de pagamento para receber pagamentos
    private BotPayment botPayment;
    
    /**
     * Classe interna para representar uma solicitação de abastecimento
     */
    private static class RefuelRequest {
        Car car;
        double amount;
        
        public RefuelRequest(Car car, double amount) {
            this.car = car;
            this.amount = amount;
        }
    }
    
    /**
     * Construtor principal da FuelStation.
     * 
     * @param stationId ID do posto
     * @param name Nome do posto
     * @param account Conta bancária do posto
     */
    // public FuelStation(String stationId, String name, Account account) {
    //     this.stationId = stationId;
    //     this.name = name;
    //     this.account = account;
    //     this.running.set(false);
    //     this.connected = false;
        
    //     // Preços padrão dos combustíveis
    //     this.dieselPrice = 5.20;
    //     this.gasolinePrice = 5.87;
    //     this.ethanolPrice = 4.59;
        
    //     // Inicializa o semáforo com 2 permissões (2 bombas)
    //     this.pumpSemaphore = new Semaphore(2, true);
        
    //     // Inicializa a fila de carros aguardando
    //     this.waitingCars = new ConcurrentLinkedQueue<>();
        
    //     // Inicializa o bot de pagamento
    //     try {
    //         this.botPayment = new BotPayment(account, "localhost", bankPort);
    //     } catch (Exception e) {
    //         logger.log(Level.WARNING, "Erro ao inicializar BotPayment: " + e.getMessage(), e);
    //         // Continua mesmo sem o bot de pagamento
    //     }
        
    //     logger.info("FuelStation " + stationId + " criada com sucesso");
    // }
    
    /**
     * Construtor alternativo que inclui informações de conexão com o banco.
     * 
     * @param stationId ID do posto
     * @param name Nome do posto
     * @param bankHost Host do AlphaBank
     * @param bankPort Porta do AlphaBank
     * @param login Login da conta no AlphaBank
     * @param senha Senha da conta no AlphaBank
     * @param initialBalance Saldo inicial da conta
     */
    public FuelStation(String stationId, String name, String bankHost, int bankPort, 
                      String login, String senha, double initialBalance) {
        this.stationId = stationId;
        this.name = name;
        this.account = new Account(senha, login,initialBalance);
        this.running.set(false);
        this.connected = false;
        this.bankPort = bankPort;
        
        // Preços padrão dos combustíveis
        this.dieselPrice = 5.20;
        this.gasolinePrice = 5.87;
        this.ethanolPrice = 4.59;
        
        // Inicializa o semáforo com 2 permissões (2 bombas)
        this.pumpSemaphore = new Semaphore(2, true);
        
        // Inicializa a fila de carros aguardando
        this.waitingCars = new ConcurrentLinkedQueue<>();
        
        // Inicializa o bot de pagamento
        try {
            this.botPayment = new BotPayment(account, "localhost", bankPort);
        } catch (Exception e) {
            logger.log(Level.WARNING, "Erro ao inicializar BotPayment: " + e.getMessage(), e);
            // Continua mesmo sem o bot de pagamento
        }
        
        logger.info("FuelStation " + stationId + " criada com sucesso");
    }
    
    /**
     * Método principal da thread.
     * Gerencia as operações do posto de combustível.
     */
    @Override
    public void run() {
        this.running.set(true);
        
        logger.info("FuelStation " + stationId + " iniciada");
        
        // Inicia o bot de pagamento se foi criado com sucesso
        if (botPayment != null) {
            botPayment.start();
            logger.info("BotPayment da FuelStation iniciado");
        } else {
            logger.warning("BotPayment não disponível para a FuelStation");
        }
        
        // Conecta ao AlphaBank se as informações de conexão foram fornecidas
        if (bankHost != null && bankPort > 0) {
            try {
                connectToBank();
            } catch (Exception e) {
                logger.log(Level.WARNING, "Erro ao conectar ao AlphaBank: " + e.getMessage(), e);
                // Continua mesmo sem conexão com o banco
            }
        }
        
        while (running.get()) {
            try {
                // Processa carros na fila de espera
                processWaitingCars();
                
                // Aguarda um curto período antes de verificar novamente
                Thread.sleep(500);
            } catch (InterruptedException e) {
                if (!running.get()) {
                    break;
                }
                Thread.currentThread().interrupt();
            } catch (Exception e) {
                logger.log(Level.SEVERE, "Erro na execução da FuelStation " + stationId, e);
                
                try {
                    // Pausa para evitar spam de erros
                    Thread.sleep(1000);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    if (!running.get()) {
                        break;
                    }
                }
            }
        }
        
        // Desconecta do banco ao finalizar
        disconnectFromBank();
        
        // Para o bot de pagamento
        if (botPayment != null) {
            botPayment.stopBot();
        }
        
        logger.info("FuelStation " + stationId + " finalizada");
    }
    
    /**
     * Processa os carros na fila de espera.
     */
    private void processWaitingCars() {
        // Verifica se há carros na fila e bombas disponíveis
        while (!waitingCars.isEmpty() && pumpSemaphore.availablePermits() > 0) {
            RefuelRequest request = waitingCars.poll();
            if (request != null && request.car != null) {
                // Inicia uma thread para processar o abastecimento
                new Thread(() -> {
                    try {
                        // Adquire uma bomba
                        pumpSemaphore.acquire();
                        
                        // Realiza o abastecimento
                        double amount = request.amount;
                        if (amount <= 0) {
                            amount = 10.0; // Quantidade padrão para encher o tanque
                        }
                        
                        // Calcula o valor e realiza o abastecimento
                        double totalValue = calculateRefuelValue(request.car, amount);
                        
                        logger.info("Iniciando abastecimento do carro " + request.car.getIdCar() + 
                                   " com " + amount + " litros. Valor: R$ " + totalValue);
                        
                        // Simula o tempo de abastecimento (reduzido para 10 segundos para testes)
                        Thread.sleep(10000);
                        
                        // Adiciona combustível ao carro
                        requestRefuel(request.car,amount);
                        
                        logger.info("Abastecimento do carro " + request.car.getIdCar() + " concluído. " +
                                   "Novo nível do tanque: " + request.car.getFuelTank() + " litros");
                        
                        // Registra o pagamento (será processado pelo Driver)
                        logger.info("Valor a ser pago: R$ " + totalValue + " pelo abastecimento do carro " + request.car.getIdCar());
                        
                    } catch (Exception e) {
                        logger.log(Level.SEVERE, "Erro ao abastecer o carro " + request.car.getIdCar(), e);
                    } finally {
                        // Libera a bomba
                        pumpSemaphore.release();
                        
                        // Notifica threads aguardando por bombas disponíveis
                        synchronized (refuelLock) {
                            refuelLock.notifyAll();
                        }
                    }
                }, "Refuel-" + request.car.getIdCar()).start();
            }
        }
    }
    
    /**
     * Para a execução do posto.
     */
    public void stopStation() {
        this.running.set(false);
        this.interrupt();
    }
    
    /**
     * Conecta ao servidor AlphaBank.
     * 
     * @return true se a conexão foi estabelecida com sucesso, false caso contrário
     */
    public boolean connectToBank() {
        if (connected) {
            return true;
        }
        
        try {
            logger.info("Conectando ao AlphaBank em " + bankHost + ":" + bankPort);
            
            // Estabelece a conexão com o servidor
            bankSocket = new Socket(bankHost, bankPort);
            bankOut = new ObjectOutputStream(bankSocket.getOutputStream());
            bankIn = new ObjectInputStream(bankSocket.getInputStream());
            
            // Envia credenciais para autenticação
            BankMessage authMessage = new BankMessage(
                    BankMessage.MessageType.AUTH,
                    login,
                    senha,
                    null
            );
            bankOut.writeObject(authMessage);
            bankOut.flush();
            
            // Recebe resposta de autenticação
            BankMessage response = (BankMessage) bankIn.readObject();
            
            if (response.getType() == BankMessage.MessageType.AUTH_OK) {
                connected = true;
                
                // Atualiza a conta com os dados do servidor
                if (response.getData() instanceof Account) {
                    account = (Account) response.getData();
                }
                
                logger.info("Conectado ao AlphaBank com sucesso. Saldo: " + account.getBalance());
                return true;
            } else {
                logger.severe("Falha na autenticação com o AlphaBank: " + response.getMessage());
                disconnectFromBank();
                return false;
            }
        } catch (Exception e) {
            logger.log(Level.SEVERE, "Erro ao conectar ao AlphaBank", e);
            disconnectFromBank();
            return false;
        }
    }
    
    /**
     * Desconecta do servidor AlphaBank.
     */
    public void disconnectFromBank() {
        if (!connected) {
            return;
        }
        
        try {
            // Envia mensagem de logout
            if (bankOut != null) {
                BankMessage logoutMessage = new BankMessage(
                        BankMessage.MessageType.LOGOUT,
                        login,
                        null,
                        null
                );
                bankOut.writeObject(logoutMessage);
                bankOut.flush();
            }
            
            // Fecha os streams e o socket
            if (bankIn != null) {
                bankIn.close();
                bankIn = null;
            }
            
            if (bankOut != null) {
                bankOut.close();
                bankOut = null;
            }
            
            if (bankSocket != null) {
                bankSocket.close();
                bankSocket = null;
            }
            
            connected = false;
            logger.info("Desconectado do AlphaBank");
        } catch (IOException e) {
            logger.log(Level.WARNING, "Erro ao desconectar do AlphaBank", e);
        }
    }
    
    /**
     * Verifica o saldo da conta no AlphaBank.
     * 
     * @return Saldo atual da conta
     */
    public double checkBalance() {
        if (!connected && !connectToBank()) {
            // Se não estiver conectado e não conseguir conectar, retorna o saldo local
            return account.getBalance();
        }
        
        try {
            // Envia solicitação de saldo
            BankMessage balanceRequest = new BankMessage(
                    BankMessage.MessageType.BALANCE,
                    login,
                    null,
                    null
            );
            bankOut.writeObject(balanceRequest);
            bankOut.flush();
            
            // Recebe resposta
            BankMessage response = (BankMessage) bankIn.readObject();
            
            if (response.getType() == BankMessage.MessageType.BALANCE_RESPONSE) {
                double balance = Double.parseDouble(response.getMessage());
                logger.info("Saldo atual: R$ " + balance);
                return balance;
            } else {
                logger.warning("Falha ao obter saldo: " + response.getMessage());
                return account.getBalance();
            }
        } catch (Exception e) {
            logger.log(Level.WARNING, "Erro ao verificar saldo", e);
            return account.getBalance();
        }
    }
    
    /**
     * Solicita abastecimento de um carro.
     * 
     * @param car Carro a ser abastecido
     * @param amount Quantidade de combustível a ser adicionada
     * @return true se a solicitação foi aceita, false caso contrário
     */
    public boolean requestRefuel(Car car, double amount) {
        if (car == null) {
            logger.warning("Solicitação de abastecimento com carro nulo");
            return false;
        }
        
        logger.info("Solicitação de abastecimento recebida para o carro " + car.getIdCar() + 
                   " com " + amount + " litros");
        
        // Adiciona o carro à fila de espera
        waitingCars.add(new RefuelRequest(car, amount));
        
        // Notifica threads aguardando por novos carros
        synchronized (refuelLock) {
            refuelLock.notify();
        }
        
        return true;
    }
    
    /**
     * Calcula o valor do abastecimento com base no tipo de combustível.
     * 
     * @param car Carro a ser abastecido
     * @param amount Quantidade de combustível em litros
     * @return Valor total do abastecimento
     */
    private double calculateRefuelValue(Car car, double amount) {
        // Calcula o valor com base no tipo de combustível
        double pricePerLiter;
        switch (car.getFuelType()) {
            case 1: // diesel
                pricePerLiter = dieselPrice;
                break;
            case 2: // gasolina
                pricePerLiter = gasolinePrice;
                break;
            case 3: // etanol
                pricePerLiter = ethanolPrice;
                break;
            default: // híbrido ou outro
                pricePerLiter = gasolinePrice;
                break;
        }
        
        return amount * pricePerLiter;
    }
    
    /**
     * Recebe pagamento de um abastecimento.
     * 
     * @param fromAccount Conta de origem
     * @param amount Valor a ser pago
     * @param description Descrição do pagamento
     * @return true se o pagamento foi processado com sucesso, false caso contrário
     */
    public boolean receivePayment(String fromAccount, double amount, String description) {
        if (amount <= 0) {
            logger.warning("Valor de pagamento inválido: " + amount);
            return false;
        }
        
        logger.info("Recebendo pagamento de R$ " + amount + " da conta " + fromAccount + 
                   ": " + description);
        
        // Atualiza o saldo da conta local
        account.deposit(amount,description);
        
        logger.info("Pagamento recebido com sucesso. Novo saldo: R$ " + account.getBalance());
        return true;
    }
    
    /**
     * Classe interna para representar mensagens trocadas com o AlphaBank.
     */
    private static class BankMessage implements Serializable {
        private static final long serialVersionUID = 1L;
        
        public enum MessageType {
            AUTH, AUTH_OK, AUTH_FAIL,
            BALANCE, BALANCE_RESPONSE,
            TRANSFER, TRANSFER_OK, TRANSFER_FAIL,
            LOGOUT
        }
        
        private MessageType type;
        private String login;
        private String message;
        private Object data;
        
        public BankMessage(MessageType type, String login, String message, Object data) {
            this.type = type;
            this.login = login;
            this.message = message;
            this.data = data;
        }
        
        public MessageType getType() {
            return type;
        }
        
        public String getLogin() {
            return login;
        }
        
        public String getMessage() {
            return message;
        }
        
        public Object getData() {
            return data;
        }
    }
    
    // Getters e setters
    
    public String getStationId() {
        return stationId;
    }
    
    public String getNome() {
        return name;
    }
    
    public Account getAccount() {
        return account;
    }
    
    public double getDieselPrice() {
        return dieselPrice;
    }
    
    public void setDieselPrice(double dieselPrice) {
        this.dieselPrice = dieselPrice;
    }
    
    public double getGasolinePrice() {
        return gasolinePrice;
    }
    
    public void setGasolinePrice(double gasolinePrice) {
        this.gasolinePrice = gasolinePrice;
    }
    
    public double getEthanolPrice() {
        return ethanolPrice;
    }
    
    public void setEthanolPrice(double ethanolPrice) {
        this.ethanolPrice = ethanolPrice;
    }
}
