package io.sim;

import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Logger;
import java.util.Base64;

import org.json.JSONObject;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import java.util.logging.Level; // Para logs mais detalhados
/**
 * Classe que representa o banco AlphaBank.
 * Gerencia contas, transações e comunicação com clientes.
 */
public class AlphaBank extends Thread {
    private static final Logger logger = Logger.getLogger(AlphaBank.class.getName());

    // Informações do banco
    private final int serverPort;
    private final String bankId;
    
    // Coleções de contas e clientes
    private final Map<String, Account> accounts;
    private final Map<String, Socket> clientSockets;
    private final Map<String, ObjectOutputStream> clientOutputStreams;
    private final Map<String, ObjectInputStream> clientInputStreams;
    
    // Controle de execução
    private ServerSocket serverSocket;
    private ExecutorService clientExecutorService;
    private AtomicBoolean running;
    
    // Criptografia
    private EncriptaDecriptaDES encriptador;
    private EncriptaDecriptaRSA rsaHandler; // Para o par de chaves RSA do servidor
    // Mapa para armazenar o EncriptaDecriptaDES específico de cada cliente, usando sua chave de sessão
    private final Map<String, EncriptaDecriptaDES> clientDesSessionEncryptors;
    
    /**
     * Construtor da classe AlphaBank.
     * 
     * @param serverPort Porta do servidor para comunicação com clientes
     */
    public AlphaBank(int serverPort) throws Exception {
        this.serverPort = serverPort;
        this.bankId = "ALPHABANK";
        this.accounts = new ConcurrentHashMap<>();
        this.clientSockets = new ConcurrentHashMap<>();
        this.clientOutputStreams = new ConcurrentHashMap<>();
        this.clientInputStreams = new ConcurrentHashMap<>();
        this.running = new AtomicBoolean(false);

        this.rsaHandler = new EncriptaDecriptaRSA(); // Gera o par de chaves RSA do servidor na inicialização
        this.clientDesSessionEncryptors = new ConcurrentHashMap<>();

        logger.info("AlphaBank inicializado na porta " + serverPort);
        // Opcional: Logar a chave pública para fins de depuração (NÃO FAÇA ISSO EM PRODUÇÃO COM A CHAVE PRIVADA)
        // logger.info("Chave Pública RSA do AlphaBank (Base64): " + rsaHandler.getPublicKeyBase64());
    }
    
    @Override
    public void run() {
        running.set(true);
        
        try {
            // Inicia o servidor para comunicação com os clientes
            startServer();
            
            // Aguarda até que o servidor seja parado
            while (running.get()) {
                Thread.sleep(1000);
            }
        } catch (Exception e) {
            logger.severe("Erro durante a execução do AlphaBank: " + e.getMessage());
            e.printStackTrace();
        } finally {
            stopServer();
        }
    }
    
    /**
     * Inicia o servidor para comunicação com os clientes.
     */
    public void startServer() {
        if (running.get()) {
            logger.warning("Servidor já está em execução.");
            return;
        }
        running.set(true);

        try {
            serverSocket = new ServerSocket(serverPort);
            clientExecutorService = Executors.newCachedThreadPool();
            
            logger.info("Servidor do AlphaBank iniciado na porta " + serverPort);
            
            // Thread para aceitar conexões
            new Thread(() -> {
                while (running.get()) {
                    try {
                        Socket clientSocket = serverSocket.accept();
                        clientSocket.setSoTimeout(5000); // Timeout de leitura
                        clientExecutorService.execute(() -> handleClientConnection(clientSocket));
                    } catch (SocketTimeoutException e) {
                        // Timeout esperado durante o accept (se configurado)
                    } catch (IOException e) {
                        if (running.get()) {
                            logger.severe("Erro ao aceitar conexão: " + e.getMessage());
                        }
                    }
                }
                logger.info("Servidor parado.");
            }).start();

        } catch (IOException e) {
            logger.severe("Erro ao iniciar servidor: " + e.getMessage());
            running.set(false);
        }
    }
    /**
     * Manipula a conexão com um cliente.
     * 
     * @param clientSocket Socket do cliente
     */
    private void handleClientConnection(Socket clientSocket) {
        String tempClientId = null; // Para identificar a conexão antes do clientId final ser lido
        ObjectOutputStream out = null;
        ObjectInputStream in = null;

        try {
            logger.info("Socket do AlphaBank criado. Criando streams...");
            out = new ObjectOutputStream(clientSocket.getOutputStream());
            out.flush();
            in = new ObjectInputStream(clientSocket.getInputStream());
            
            // 1. ETAPA PRELIMINAR: Receber um ID de cliente temporário/identificador de conexão
            // Este ID é usado para rastrear esta conexão específica durante a negociação da chave.
            // Pode ser o mesmo ID que você já usava (driverId + UUID).
            tempClientId = (String) in.readObject();
            logger.info("Cliente (" + tempClientId + ") conectado, iniciando negociação de chave.");
            
            // Armazena streams temporariamente para este cliente
            clientOutputStreams.put(tempClientId, out);
            clientInputStreams.put(tempClientId, in);
            clientSockets.put(tempClientId, clientSocket);
            
            // 2. ENVIAR CHAVE PÚBLICA RSA DO SERVIDOR PARA O CLIENTE
            // Envia os bytes da chave pública, codificados em Base64 para facilitar a transmissão como String.
            out.writeObject(rsaHandler.getPublicKeyBase64());
            out.flush();
            logger.info("Chave pública RSA enviada para o cliente: " + tempClientId);
            
            // 3. RECEBER CHAVE DE SESSÃO DES (CRIPTOGRAFADA COM RSA) DO CLIENTE
            // O cliente gerou uma chave DES, criptografou com a chave pública RSA do servidor e enviou.
            byte[] encryptedDesSessionKey = (byte[]) in.readObject();
            logger.info("Chave DES de sessão criptografada recebida de: " + tempClientId);

            // 4. DESCRIPTOGRAFAR A CHAVE DE SESSÃO DES USANDO A CHAVE PRIVADA RSA DO SERVIDOR
            byte[] desSessionKeyBytes = rsaHandler.descriptografarComPrivateKey(encryptedDesSessionKey);

            // Cria um EncriptaDecriptaDES para este cliente com a chave de sessão negociada
            EncriptaDecriptaDES clientSessionEncryptor = new EncriptaDecriptaDES(desSessionKeyBytes);
            clientDesSessionEncryptors.put(tempClientId, clientSessionEncryptor);
            logger.info("Chave DES de sessão estabelecida para o cliente: " + tempClientId);

            // 5. ENVIAR CONFIRMAÇÃO DE CONEXÃO SEGURA (CRIPTOGRAFADA COM A NOVA CHAVE DES DE SESSÃO)
            String confirmationMessage = "CONNECTED_SECURELY";
            // Criptografa a confirmação usando a chave DES de sessão recém-estabelecida
            String encryptedConfirmation = clientSessionEncryptor.criptografar(confirmationMessage);
            out.writeObject(encryptedConfirmation);
            out.flush();
            logger.info("Confirmação de conexão segura enviada para: " + tempClientId);

            // A partir daqui, a comunicação é criptografada com a chave DES de sessão
            // O loop de processamento de comandos usará 'clientSessionEncryptor'
            while (running.get() && !clientSocket.isClosed()) {
                try {
                    String encryptedCommand = (String) in.readObject();
                    // Usa o encriptador DES específico da sessão deste cliente
                    String command = clientSessionEncryptor.descriptografar(encryptedCommand);

                    JSONObject response = processCommand(tempClientId, command); // tempClientId é o identificador da sessão

                    String encryptedResponse = clientSessionEncryptor.criptografar(response.toString());
                    out.writeObject(encryptedResponse);
                    out.flush();
                } catch (SocketTimeoutException ste) {
                    // Timeout é esperado, apenas continue
                    if(!running.get() || clientSocket.isClosed()) break;
                } catch (IOException | ClassNotFoundException e) {
                    if (running.get() && !clientSocket.isClosed()) {
                        logger.log(Level.WARNING, "Conexão com cliente " + tempClientId + " perdida ou erro de stream: " + e.getMessage());
                    }
                    break; // Sai do loop de processamento de comandos
                } catch (Exception e) { // Captura outras exceções, como de criptografia
                    if (running.get() && !clientSocket.isClosed()) {
                        logger.log(Level.SEVERE, "Erro ao processar comando criptografado do cliente " + tempClientId + ": " + e.getMessage(), e);
                        // Poderia tentar enviar uma mensagem de erro criptografada ao cliente
                    }
                    break; 
                }
            }
        } catch (Exception e) {
            // Logar o erro de negociação de chave ou conexão inicial
            logger.log(Level.SEVERE, "Erro durante negociação de chave ou conexão inicial com cliente " + 
                                    (tempClientId != null ? tempClientId : clientSocket.getRemoteSocketAddress()) + 
                                    ": " + e.getMessage(), e);
        } finally {
            // Limpar recursos para este cliente
            if (tempClientId != null) {
                clientSockets.remove(tempClientId);
                clientOutputStreams.remove(tempClientId);
                clientInputStreams.remove(tempClientId);
                clientDesSessionEncryptors.remove(tempClientId); // Remove o encriptador da sessão
                logger.info("Cliente " + tempClientId + " desconectado e recursos limpos.");
            }
            try {
                if (clientSocket != null && !clientSocket.isClosed()) {
                    clientSocket.close();
                }
            } catch (IOException ex) {
                logger.log(Level.WARNING, "Erro ao fechar socket do cliente.", ex);
            }
        }
    }
    
    /**
     * Obtém o ID do cliente a partir do socket.
     * 
     * @param socket Socket do cliente
     * @return ID do cliente ou null se não encontrado
     */
    private String getClientIdFromSocket(Socket socket) {
        for (Map.Entry<String, Socket> entry : clientSockets.entrySet()) {
            if (entry.getValue().equals(socket)) {
                return entry.getKey();
            }
        }
        return null;
    }
    
    /**
     * Processa um comando recebido de um cliente.
     * 
     * @param clientId ID do cliente
     * @param commandStr Comando recebido em formato JSON
     * @return Resposta em formato JSON
     */
    private JSONObject processCommand(String clientId, String commandStr) {
        try {
            JSONObject command = new JSONObject(commandStr);
            String action = command.getString("action");
            
            logger.info("Comando recebido do cliente " + clientId + ": " + action);
            
            JSONObject response = new JSONObject();
            response.put("clientId", clientId);
            response.put("action", action);
            response.put("status", "success");
            
            switch (action) {
                case "CREATE_ACCOUNT":
                    String ownerName = command.getString("ownerName");
                    double initialBalance = command.getDouble("initialBalance");
                    String accountId = command.optString("accountId", null);
                    
                    Account account = createAccount(accountId, ownerName, initialBalance);
                    if (account != null) {
                        response.put("accountId", account.getSenha());
                        response.put("balance", account.getBalance());
                    } else {
                        response.put("status", "error");
                        response.put("message", "Falha ao criar conta");
                    }
                    break;
                    
                case "GET_BALANCE":
                    accountId = command.getString("accountId");
                    Account acc = getAccount(accountId);
                    
                    if (acc != null) {
                        response.put("balance", acc.getBalance());
                    } else {
                        response.put("status", "error");
                        response.put("message", "Conta não encontrada");
                    }
                    break;
                    
                case "DEPOSIT":
                    accountId = command.getString("accountId");
                    double amount = command.getDouble("amount");
                    String description = command.getString("description");
                    
                    boolean depositSuccess = deposit(accountId, amount, description);
                    if (depositSuccess) {
                        Account updatedAcc = getAccount(accountId);
                        response.put("balance", updatedAcc.getBalance());
                    } else {
                        response.put("status", "error");
                        response.put("message", "Falha ao realizar depósito");
                    }
                    break;
                    
                case "WITHDRAW":
                    accountId = command.getString("accountId");
                    amount = command.getDouble("amount");
                    description = command.getString("description");
                    
                    boolean withdrawSuccess = withdraw(accountId, amount, description);
                    if (withdrawSuccess) {
                        Account updatedAcc = getAccount(accountId);
                        response.put("balance", updatedAcc.getBalance());
                    } else {
                        response.put("status", "error");
                        response.put("message", "Falha ao realizar saque");
                    }
                    break;
                    
                case "TRANSFER":
                    String sourceAccountId = command.getString("sourceAccountId");
                    String destinationAccountId = command.getString("destinationAccountId");
                    amount = command.getDouble("amount");
                    description = command.getString("description");
                    
                    boolean transferSuccess = transfer(sourceAccountId, destinationAccountId, amount, description);
                    if (transferSuccess) {
                        Account sourceAcc = getAccount(sourceAccountId);
                        response.put("balance", sourceAcc.getBalance());
                    } else {
                        response.put("status", "error");
                        response.put("message", "Falha ao realizar transferência");
                    }
                    break;
                    
                case "GET_STATEMENT":
                    accountId = command.getString("accountId");
                    Account statementAcc = getAccount(accountId);
                    
                    if (statementAcc != null) {
                        response.put("statement", statementAcc.getStatement());
                    } else {
                        response.put("status", "error");
                        response.put("message", "Conta não encontrada");
                    }
                    break;
                    
                case "ACTIVATE_ACCOUNT":
                    accountId = command.getString("accountId");
                    boolean activateSuccess = activateAccount(accountId);
                    
                    if (!activateSuccess) {
                        response.put("status", "error");
                        response.put("message", "Falha ao ativar conta");
                    }
                    break;
                    
                case "DEACTIVATE_ACCOUNT":
                    accountId = command.getString("accountId");
                    boolean deactivateSuccess = deactivateAccount(accountId);
                    
                    if (!deactivateSuccess) {
                        response.put("status", "error");
                        response.put("message", "Falha ao desativar conta");
                    }
                    break;
                    
                case "AUTHENTICATE":
                    String login = command.getString("login");
                    String password = command.getString("password");
                    String clientType = command.getString("clientType");
                    
                    boolean authSuccess = authenticateClient(login, password, clientType);
                    if (authSuccess) {
                        response.put("message", "Autenticação bem-sucedida");
                    } else {
                        response.put("status", "error");
                        response.put("message", "Falha na autenticação");
                    }
                    break;
                    
                default:
                    logger.warning("Comando desconhecido recebido do cliente " + clientId + ": " + action);
                    response.put("status", "error");
                    response.put("message", "Comando desconhecido");
                    break;
            }
            
            return response;
        } catch (Exception e) {
            logger.severe("Erro ao processar comando: " + e.getMessage());
            
            JSONObject errorResponse = new JSONObject();
            errorResponse.put("status", "error");
            errorResponse.put("message", "Erro ao processar comando: " + e.getMessage());
            return errorResponse;
        }
    }
    
    /**
     * Autentica um cliente com base no login, senha e tipo.
     * 
     * @param login Login do cliente
     * @param password Senha do cliente
     * @param clientType Tipo do cliente (DRIVER, COMPANY, etc.)
     * @return true se a autenticação for bem-sucedida, false caso contrário
     */
    private boolean authenticateClient(String login, String password, String clientType) {
        // Implementação simplificada - em um sistema real, verificaria credenciais em um banco de dados
        logger.info("Tentativa de autenticação: login=" + login + ", tipo=" + clientType);
        
        // Para fins de teste, aceita qualquer autenticação
        return true;
    }
    
    /**
     * Cria uma nova conta.
     * 
     * @param accountId ID da conta (opcional, pode ser null para gerar automaticamente)
     * @param ownerName Nome do titular da conta
     * @param initialBalance Saldo inicial da conta
     * @return Conta criada ou null se falhar
     */
    public synchronized Account createAccount(String accountId, String ownerName, double initialBalance) {
        try {
            Account account;
            
            if (accountId != null && !accountId.isEmpty()) {
                // Verifica se já existe uma conta com o ID fornecido
                if (accounts.containsKey(accountId)) {
                    logger.warning("Tentativa de criar conta com ID já existente: " + accountId);
                    return null;
                }
                
                account = new Account(accountId, ownerName, initialBalance);
            } else {
                account = new Account(ownerName, initialBalance);
            }
            
            accounts.put(account.getSenha(), account);
            logger.info("Conta criada: " + account.getSenha() + " - Titular: " + ownerName);
            
            return account;
        } catch (Exception e) {
            logger.severe("Erro ao criar conta: " + e.getMessage());
            return null;
        }
    }
    
    /**
     * Adiciona uma conta existente ao banco.
     * 
     * @param account Conta a ser adicionada
     * @return true se a conta foi adicionada com sucesso, false caso contrário
     */
    public synchronized boolean addAccount(Account account) {
        try {
            if (account == null) {
                return false;
            }
            
            if (accounts.containsKey(account.getSenha())) {
                logger.warning("Tentativa de adicionar conta com ID já existente: " + account.getSenha());
                return false;
            }
            
            accounts.put(account.getSenha(), account);
            logger.info("Conta adicionada: " + account.getSenha() + " - Titular: " + account.getLogin());
            
            return true;
        } catch (Exception e) {
            logger.severe("Erro ao adicionar conta: " + e.getMessage());
            return false;
        }
    }
    
    /**
     * Obtém uma conta pelo ID.
     * 
     * @param accountId ID da conta
     * @return Conta encontrada ou null se não existir
     */
    public Account getAccount(String accountId) {
        return accounts.get(accountId);
    }
    
    /**
     * Realiza um depósito em uma conta.
     * 
     * @param accountId ID da conta
     * @param amount Valor a ser depositado
     * @param description Descrição da transação
     * @return true se o depósito foi realizado com sucesso, false caso contrário
     */
    public boolean deposit(String accountId, double amount, String description) {
        try {
            Account account = accounts.get(accountId);
            
            if (account == null) {
                logger.warning("Tentativa de depósito em conta inexistente: " + accountId);
                return false;
            }
            
            if (!account.isActive()) {
                logger.warning("Tentativa de depósito em conta inativa: " + accountId);
                return false;
            }
            
            if (amount <= 0) {
                logger.warning("Tentativa de depósito com valor inválido: " + amount);
                return false;
            }
            
            account.deposit(amount, description);
            logger.info("Depósito realizado: " + accountId + " - Valor: " + amount + " - Descrição: " + description);
            
            return true;
        } catch (Exception e) {
            logger.severe("Erro ao realizar depósito: " + e.getMessage());
            return false;
        }
    }
    
    /**
     * Realiza um saque em uma conta.
     * 
     * @param accountId ID da conta
     * @param amount Valor a ser sacado
     * @param description Descrição da transação
     * @return true se o saque foi realizado com sucesso, false caso contrário
     */
    public boolean withdraw(String accountId, double amount, String description) {
        try {
            Account account = accounts.get(accountId);
            
            if (account == null) {
                logger.warning("Tentativa de saque em conta inexistente: " + accountId);
                return false;
            }
            
            if (!account.isActive()) {
                logger.warning("Tentativa de saque em conta inativa: " + accountId);
                return false;
            }
            
            if (amount <= 0) {
                logger.warning("Tentativa de saque com valor inválido: " + amount);
                return false;
            }
            
            if (account.getBalance() < amount) {
                logger.warning("Tentativa de saque com saldo insuficiente: " + accountId + " - Saldo: " + account.getBalance() + " - Valor: " + amount);
                return false;
            }
            
            account.withdraw(amount, description);
            logger.info("Saque realizado: " + accountId + " - Valor: " + amount + " - Descrição: " + description);
            
            return true;
        } catch (Exception e) {
            logger.severe("Erro ao realizar saque: " + e.getMessage());
            return false;
        }
    }
    
    /**
     * Realiza uma transferência entre contas.
     * 
     * @param sourceAccountId ID da conta de origem
     * @param destinationAccountId ID da conta de destino
     * @param amount Valor a ser transferido
     * @param description Descrição da transação
     * @return true se a transferência foi realizada com sucesso, false caso contrário
     */
    public boolean transfer(String sourceAccountId, String destinationAccountId, double amount, String description) {
        try {
            Account sourceAccount = accounts.get(sourceAccountId);
            Account destinationAccount = accounts.get(destinationAccountId);
            
            if (sourceAccount == null) {
                logger.warning("Tentativa de transferência de conta inexistente: " + sourceAccountId);
                return false;
            }
            
            if (destinationAccount == null) {
                logger.warning("Tentativa de transferência para conta inexistente: " + destinationAccountId);
                return false;
            }
            
            if (!sourceAccount.isActive() || !destinationAccount.isActive()) {
                logger.warning("Tentativa de transferência com conta inativa");
                return false;
            }
            
            if (amount <= 0) {
                logger.warning("Tentativa de transferência com valor inválido: " + amount);
                return false;
            }
            
            if (sourceAccount.getBalance() < amount) {
                logger.warning("Tentativa de transferência com saldo insuficiente: " + sourceAccountId + " - Saldo: " + sourceAccount.getBalance() + " - Valor: " + amount);
                return false;
            }
            
            sourceAccount.withdraw(amount, "Transferência para " + destinationAccountId + ": " + description);
            destinationAccount.deposit(amount, "Transferência de " + sourceAccountId + ": " + description);
            
            logger.info("Transferência realizada: " + sourceAccountId + " -> " + destinationAccountId + " - Valor: " + amount + " - Descrição: " + description);
            
            return true;
        } catch (Exception e) {
            logger.severe("Erro ao realizar transferência: " + e.getMessage());
            return false;
        }
    }
    
    /**
     * Ativa uma conta.
     * 
     * @param accountId ID da conta
     * @return true se a conta foi ativada com sucesso, false caso contrário
     */
    public boolean activateAccount(String accountId) {
        try {
            Account account = accounts.get(accountId);
            
            if (account == null) {
                logger.warning("Tentativa de ativar conta inexistente: " + accountId);
                return false;
            }
            
            account.activate();
            logger.info("Conta ativada: " + accountId);
            
            return true;
        } catch (Exception e) {
            logger.severe("Erro ao ativar conta: " + e.getMessage());
            return false;
        }
    }
    
    /**
     * Desativa uma conta.
     * 
     * @param accountId ID da conta
     * @return true se a conta foi desativada com sucesso, false caso contrário
     */
    public boolean deactivateAccount(String accountId) {
        try {
            Account account = accounts.get(accountId);
            
            if (account == null) {
                logger.warning("Tentativa de desativar conta inexistente: " + accountId);
                return false;
            }
            
            account.deactivate();
            logger.info("Conta desativada: " + accountId);
            
            return true;
        } catch (Exception e) {
            logger.severe("Erro ao desativar conta: " + e.getMessage());
            return false;
        }
    }
    
    /**
     * Para o servidor.
     */
    public void stopServer() {
        if (!running.get()) {
            logger.warning("Servidor já está parado.");
            return;
        }
        
        running.set(false);
        
        try {
            // Fecha todas as conexões com clientes
            for (Socket socket : clientSockets.values()) {
                try {
                    socket.close();
                } catch (IOException e) {
                    // Ignora erros ao fechar sockets
                }
            }
            
            // Limpa as coleções
            clientSockets.clear();
            clientOutputStreams.clear();
            clientInputStreams.clear();
            
            // Encerra o executor de threads
            if (clientExecutorService != null) {
                clientExecutorService.shutdown();
            }
            
            // Fecha o socket do servidor
            if (serverSocket != null && !serverSocket.isClosed()) {
                serverSocket.close();
            }
            
            logger.info("Servidor do AlphaBank parado.");
        } catch (IOException e) {
            logger.severe("Erro ao parar servidor: " + e.getMessage());
        }
    }
    
    /**
     * Obtém a lista de contas.
     * 
     * @return Lista de contas
     */
    public List<Account> getAccounts() {
        return new ArrayList<>(accounts.values());
    }
    
    /**
     * Obtém o número de contas.
     * 
     * @return Número de contas
     */
    public int getAccountCount() {
        return accounts.size();
    }
    
    /**
     * Obtém o número de clientes conectados.
     * 
     * @return Número de clientes conectados
     */
    public int getConnectedClientCount() {
        return clientSockets.size();
    }
    
    /**
     * Verifica se o servidor está em execução.
     * 
     * @return true se o servidor está em execução, false caso contrário
     */
    public boolean isRunning() {
        return running.get();
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
     * Obtém o ID do banco.
     * 
     * @return ID do banco
     */
    public String getBankId() {
        return bankId;
    }
}
