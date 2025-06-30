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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Logger;
import java.util.Base64;
import java.util.concurrent.TimeUnit;

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
    private Map<String, ObjectOutputStream> clientOutputStreams;
    private Map<String, Socket> clientSockets;
    private Map<String, ObjectInputStream> clientInputStreams;
    private Map<String, EncriptaDecriptaDES> clientDesSessionEncryptors;

    // Controle de execução
    private ServerSocket serverSocket;
    private ExecutorService clientExecutorService;
    private AtomicBoolean running;
    private CountDownLatch readyLatch;
    
    // Criptografia
    private EncriptaDecriptaDES encriptador;
    private EncriptaDecriptaRSA rsaHandler; // Para o par de chaves RSA do servidor
    // Mapa para armazenar o EncriptaDecriptaDES específico de cada cliente, usando sua chave de sessão
    //private final Map<String, EncriptaDecriptaDES> clientDesSessionEncryptors;
    
    /**
     * Construtor da classe AlphaBank.
     * * @param serverPort Porta do servidor para comunicação com clientes
     */
    public AlphaBank(int serverPort, CountDownLatch readyLatch) throws Exception {
        this.readyLatch = readyLatch;
        this.serverPort = serverPort;
        this.bankId = "ALPHABANK"; // Este ID é usado internamente, não para conexão de rede pelo nome.
        this.accounts = new ConcurrentHashMap<>();
        this.clientSockets = new ConcurrentHashMap<>();
        this.clientOutputStreams = new ConcurrentHashMap<>();
        this.clientInputStreams = new ConcurrentHashMap<>();
        this.running = new AtomicBoolean(false);

        this.rsaHandler = new EncriptaDecriptaRSA(); // Gera o par de chaves RSA do servidor na inicialização
        this.clientDesSessionEncryptors = new ConcurrentHashMap<>();

        // startServer(); // Removido para iniciar explicitamente via start() da Thread
        logger.info("AlphaBank inicializado na porta " + serverPort);
    }
    
    @Override
    public void run() {
        startServer(); // Inicia o servidor quando a thread AlphaBank é iniciada
         if (this.readyLatch != null) {
                this.readyLatch.countDown();
            }
        try {
            while (running.get()) {
                Thread.sleep(1000); // Mantém a thread principal do AlphaBank viva
            }
        } catch (InterruptedException e) {
            logger.info("Thread AlphaBank interrompida.");
            Thread.currentThread().interrupt();
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
        
        try {
            serverSocket = new ServerSocket(serverPort);
            running.set(true); // Define running como true ANTES de iniciar o loop de accept
            clientExecutorService = Executors.newCachedThreadPool();
            
            logger.info("Servidor do AlphaBank iniciado na porta " + serverPort);
            
            // Thread para aceitar conexões
            new Thread(() -> {
                while (running.get()) {
                    try {
                        Socket clientSocket = serverSocket.accept();
                        clientSocket.setSoTimeout(5000); // Timeout de leitura
                        logger.info("Nova conexão de cliente recebida: " + clientSocket.getRemoteSocketAddress());
                        clientExecutorService.execute(() -> handleClientConnection(clientSocket));
                    } catch (SocketTimeoutException e) {
                        // Timeout esperado durante o accept (se configurado), continua o loop se running for true
                        if (!running.get()) break;
                    } catch (IOException e) {
                        if (running.get()) { // Só loga erro se o servidor deveria estar rodando
                            logger.log(Level.SEVERE, "Erro ao aceitar conexão: " + e.getMessage(), e);
                        } else {
                            logger.info("ServerSocket fechado, parando de aceitar conexões.");
                            break; 
                        }
                    }
                }
                logger.info("Loop de aceitação de conexões do AlphaBank terminado.");
            }).start();

        } catch (IOException e) {
            logger.log(Level.SEVERE, "Erro CRÍTICO ao iniciar servidor AlphaBank: " + e.getMessage(), e);
            running.set(false); // Garante que running seja false se o ServerSocket falhar
        }
    }

    /**
     * Manipula a conexão com um cliente.
     * * @param clientSocket Socket do cliente
     */
    private void handleClientConnection(Socket clientSocket) {
        String tempClientId = clientSocket.getRemoteSocketAddress().toString(); // ID temporário baseado no endereço
        ObjectOutputStream out = null;
        ObjectInputStream in = null;
        EncriptaDecriptaDES clientSessionEncryptor = null; // Deve ser específico para esta conexão

        try {
            logger.info("ALPHA_HANDLER (" + tempClientId + "): Criando streams...");
            out = new ObjectOutputStream(clientSocket.getOutputStream());
            out.flush();
            in = new ObjectInputStream(clientSocket.getInputStream());
            
            // 1. ETAPA PRELIMINAR: Receber um ID de cliente
            String receivedClientId = (String) in.readObject();
            tempClientId = receivedClientId; // Atualiza para o ID enviado pelo cliente
            logger.info("ALPHA_HANDLER (" + tempClientId + "): Cliente conectado, iniciando negociação de chave.");
            
            // Armazena streams e socket para este cliente
            clientOutputStreams.put(tempClientId, out);
            clientInputStreams.put(tempClientId, in);
            clientSockets.put(tempClientId, clientSocket);
            
            // 2. ENVIAR CHAVE PÚBLICA RSA DO SERVIDOR PARA O CLIENTE
            out.writeObject(rsaHandler.getPublicKeyBase64());
            out.flush();
            logger.info("ALPHA_HANDLER (" + tempClientId + "): Chave pública RSA enviada.");
            
            // 3. RECEBER CHAVE DE SESSÃO DES (CRIPTOGRAFADA COM RSA) DO CLIENTE
            byte[] encryptedDesSessionKey = (byte[]) in.readObject();
            logger.info("ALPHA_HANDLER (" + tempClientId + "): Chave DES de sessão criptografada recebida.");

            // 4. DESCRIPTOGRAFAR A CHAVE DE SESSÃO DES USANDO A CHAVE PRIVADA RSA DO SERVIDOR
            byte[] desSessionKeyBytes = rsaHandler.descriptografarComPrivateKey(encryptedDesSessionKey);

            clientSessionEncryptor = new EncriptaDecriptaDES(desSessionKeyBytes);
            clientDesSessionEncryptors.put(tempClientId, clientSessionEncryptor);
            logger.info("ALPHA_HANDLER (" + tempClientId + "): Chave DES de sessão estabelecida.");

            // 5. ENVIAR CONFIRMAÇÃO DE CONEXÃO SEGURA (CRIPTOGRAFADA COM A NOVA CHAVE DES DE SESSÃO)
            String confirmationMessage = "CONNECTED_SECURELY";
            String encryptedConfirmation = clientSessionEncryptor.criptografar(confirmationMessage);
            out.writeObject(encryptedConfirmation);
            out.flush();
            logger.info("ALPHA_HANDLER (" + tempClientId + "): Confirmação de conexão segura enviada.");

            while (running.get() && !clientSocket.isClosed()) {
                try {
                    String encryptedCommand = (String) in.readObject();
                    String command = clientSessionEncryptor.descriptografar(encryptedCommand);

                    JSONObject response = processCommand(tempClientId, command); 

                    String encryptedResponse = clientSessionEncryptor.criptografar(response.toString());
                    out.writeObject(encryptedResponse);
                    out.flush();
                } catch (SocketTimeoutException ste) {
                    if(!running.get() || clientSocket.isClosed()) break;
                } catch (java.io.EOFException eofe) {
                    logger.warning("ALPHA_HANDLER (" + tempClientId + "): EOFException. Cliente provavelmente fechou a conexão.");
                    break;
                }
                catch (IOException | ClassNotFoundException e) {
                    if (running.get() && !clientSocket.isClosed()) {
                        logger.log(Level.WARNING, "ALPHA_HANDLER (" + tempClientId + "): Conexão perdida ou erro de stream: " + e.getMessage());
                    }
                    break; 
                } catch (Exception e) { 
                    if (running.get() && !clientSocket.isClosed()) {
                        logger.log(Level.SEVERE, "ALPHA_HANDLER (" + tempClientId + "): Erro ao processar comando criptografado: " + e.getMessage(), e);
                    }
                    break; 
                }
            }
        } catch (java.io.EOFException eofe) {
            logger.warning("ALPHA_HANDLER (" + tempClientId + "): EOFException durante handshake. Cliente provavelmente fechou a conexão prematuramente.");
        } catch (Exception e) {
            logger.log(Level.SEVERE, "ALPHA_HANDLER (" + tempClientId + "): Erro durante negociação de chave ou conexão inicial: " + e.getMessage(), e);
        } finally {
            logger.info("ALPHA_HANDLER (" + tempClientId + "): Encerrando handler.");
            if (tempClientId != null) { // Usa o ID do cliente se disponível
                clientSockets.remove(tempClientId);
                clientOutputStreams.remove(tempClientId);
                clientInputStreams.remove(tempClientId);
                clientDesSessionEncryptors.remove(tempClientId); 
                logger.info("ALPHA_HANDLER (" + tempClientId + "): Cliente desconectado e recursos limpos.");
            }
            try {
                if (in != null) in.close();
                if (out != null) out.close();
                if (clientSocket != null && !clientSocket.isClosed()) {
                    clientSocket.close();
                }
            } catch (IOException ex) {
                logger.log(Level.WARNING, "ALPHA_HANDLER (" + tempClientId + "): Erro ao fechar socket/streams do cliente.", ex);
            }
        }
    }
    
    /**
     * Processa um comando recebido de um cliente.
     * * @param clientId ID do cliente
     * @param commandStr Comando recebido em formato JSON
     * @return Resposta em formato JSON
     */
    private JSONObject processCommand(String clientId, String commandStr) {
        JSONObject response = new JSONObject();
        try {
            JSONObject command = new JSONObject(commandStr);
            String action = command.getString("action");
            
            logger.info("Comando recebido do cliente " + clientId + ": " + action + " - Payload: " + commandStr);
            
            response.put("clientId", clientId);
            response.put("action", action);
            response.put("status", "success"); // Default to success, change on error
            
            String accountId, ownerName, description, sourceAccountId, destinationAccountId, login, password, clientType;
            double initialBalance, amount;
            Account acc, sourceAcc, destAcc, statementAcc;
            boolean successOperation;

            switch (action) {
                case "CREATE_ACCOUNT":
                    ownerName = command.getString("ownerName");
                    initialBalance = command.getDouble("initialBalance");
                    accountId = command.optString("accountId", null); // Pode ser nulo
                    
                    Account newAccount = createAccount(accountId, ownerName, initialBalance);
                    if (newAccount != null) {
                        response.put("accountId", newAccount.getSenha()); // Retorna o ID real da conta (senha)
                        response.put("balance", newAccount.getBalance());
                        response.put("message", "Conta criada com sucesso.");
                    } else {
                        response.put("status", "error");
                        response.put("message", "Falha ao criar conta. ID de conta pode já existir ou dados inválidos.");
                    }
                    break;
                    
                case "GET_BALANCE":
                    accountId = command.getString("accountId");
                    acc = getAccount(accountId);
                    
                    if (acc != null) {
                        response.put("balance", acc.getBalance());
                    } else {
                        response.put("status", "error");
                        response.put("message", "Conta não encontrada");
                    }
                    break;
                    
                case "DEPOSIT":
                    accountId = command.getString("accountId");
                    amount = command.getDouble("amount");
                    description = command.optString("description", "Depósito");
                    
                    successOperation = deposit(accountId, amount, description);
                    if (successOperation) {
                        Account updatedAcc = getAccount(accountId);
                        response.put("balance", updatedAcc.getBalance());
                        response.put("message", "Depósito realizado com sucesso.");
                    } else {
                        response.put("status", "error");
                        response.put("message", "Falha ao realizar depósito. Verifique se a conta existe e está ativa.");
                    }
                    break;
                    
                case "WITHDRAW":
                    accountId = command.getString("accountId");
                    amount = command.getDouble("amount");
                    description = command.optString("description", "Saque");
                    
                    successOperation = withdraw(accountId, amount, description);
                    if (successOperation) {
                        Account updatedAcc = getAccount(accountId);
                        response.put("balance", updatedAcc.getBalance());
                        response.put("message", "Saque realizado com sucesso.");
                    } else {
                        response.put("status", "error");
                        response.put("message", "Falha ao realizar saque. Verifique saldo, conta ativa e valor.");
                    }
                    break;
                    
                case "TRANSFER": // Ação de transferência direta
                    sourceAccountId = command.getString("sourceAccountId");
                    destinationAccountId = command.getString("destinationAccountId");
                    amount = command.getDouble("amount");
                    description = command.optString("description", "Transferência");
                    
                    successOperation = transfer(sourceAccountId, destinationAccountId, amount, description);
                    if (successOperation) {
                        Account sourceAccUpdated = getAccount(sourceAccountId);
                        response.put("sourceAccountBalance", sourceAccUpdated.getBalance());
                        response.put("message", "Transferência realizada com sucesso.");
                    } else {
                        response.put("status", "error");
                        response.put("message", "Falha ao realizar transferência. Verifique contas, saldo e status.");
                    }
                    break;

                // CORREÇÃO: Adicionar case para "PAYMENT"
                case "PAYMENT":
                    sourceAccountId = command.getString("sourceAccount"); // Assumindo que o BotPayment envia 'sourceAccount'
                    destinationAccountId = command.getString("destinationAccount");
                    amount = command.getDouble("amount");
                    description = command.getString("description"); // A descrição já é um JSON string de PaymentRecord
                    String paymentId = command.optString("paymentId", "N/A");

                    logger.info("ALPHA_HANDLER (" + clientId + "): Processando PAYMENT de " + sourceAccountId + 
                                " para " + destinationAccountId + ", Valor: " + amount + ", ID Pagamento: " + paymentId);

                    successOperation = transfer(sourceAccountId, destinationAccountId, amount, "Pagamento ID: " + paymentId + " - " + description);
                    if (successOperation) {
                        Account sourceAccAfterPayment = getAccount(sourceAccountId);
                        response.put("sourceAccountBalance", sourceAccAfterPayment.getBalance());
                        response.put("paymentId", paymentId);
                        response.put("message", "Pagamento processado com sucesso.");
                        logger.info("ALPHA_HANDLER (" + clientId + "): Pagamento ID " + paymentId + " processado com SUCESSO.");
                    } else {
                        response.put("status", "error");
                        response.put("paymentId", paymentId);
                        response.put("message", "Falha ao processar pagamento. Verifique contas, saldo e status.");
                        logger.warning("ALPHA_HANDLER (" + clientId + "): Falha ao processar pagamento ID " + paymentId + ".");
                    }
                    break;
                    
                case "GET_STATEMENT":
                    accountId = command.getString("accountId");
                    statementAcc = getAccount(accountId);
                    
                    if (statementAcc != null) {
                        response.put("statement", statementAcc.getStatement()); // getStatement() deve retornar algo serializável (ex: List<String> ou JSON)
                    } else {
                        response.put("status", "error");
                        response.put("message", "Conta não encontrada");
                    }
                    break;
                    
                case "ACTIVATE_ACCOUNT":
                    accountId = command.getString("accountId");
                    successOperation = activateAccount(accountId);
                    if (successOperation) {
                        response.put("message", "Conta ativada com sucesso.");
                    } else {
                        response.put("status", "error");
                        response.put("message", "Falha ao ativar conta.");
                    }
                    break;
                    
                case "DEACTIVATE_ACCOUNT":
                    accountId = command.getString("accountId");
                    successOperation = deactivateAccount(accountId);
                    if (successOperation) {
                        response.put("message", "Conta desativada com sucesso.");
                    } else {
                        response.put("status", "error");
                        response.put("message", "Falha ao desativar conta.");
                    }
                    break;
                    
                case "AUTHENTICATE":
                    login = command.getString("login");
                    password = command.getString("password");
                    clientType = command.getString("clientType"); // Ex: "DRIVER", "COMPANY_BOT", "FUEL_STATION_BOT"
                    
                    successOperation = authenticateClient(login, password, clientType); // A lógica de autenticação real seria mais complexa
                    if (successOperation) {
                        response.put("message", "Autenticação bem-sucedida para " + clientType + " " + login);
                    } else {
                        response.put("status", "error");
                        response.put("message", "Falha na autenticação para " + clientType + " " + login);
                    }
                    break;
                    
                default:
                    logger.warning("Comando desconhecido '" + action + "' recebido do cliente " + clientId);
                    response.put("status", "error");
                    response.put("message", "Comando desconhecido: " + action);
                    break;
            }
            
            return response;

        } catch (org.json.JSONException jsonEx) {
            logger.log(Level.SEVERE, "Erro de JSON ao processar comando de " + clientId + ": " + commandStr, jsonEx);
            response.put("status", "error");
            response.put("message", "Erro de formato JSON no comando: " + jsonEx.getMessage());
            return response;
        } catch (Exception e) {
            logger.log(Level.SEVERE, "Erro GERAL ao processar comando de " + clientId + ": " + commandStr, e);
            response.put("status", "error");
            response.put("message", "Erro interno no servidor ao processar comando: " + e.getMessage());
            return response;
        }
    }
    
    /**
     * Autentica um cliente com base no login, senha e tipo.
     * * @param login Login do cliente
     * @param password Senha do cliente
     * @param clientType Tipo do cliente (DRIVER, COMPANY, etc.)
     * @return true se a autenticação for bem-sucedida, false caso contrário
     */
    private boolean authenticateClient(String login, String password, String clientType) {
        logger.info("Tentativa de autenticação: login=" + login + ", tipo=" + clientType);
        Account account = accounts.get(login); // Assume que o login é o ID da conta para simplificar
        if (account != null && account.getSenha().equals(password)) {
             // Em um sistema real, você também pode verificar o clientType se necessário
            logger.info("Autenticação bem-sucedida para: " + login);
            return true;
        }
        logger.warning("Falha na autenticação para: " + login);
        return false;
    }
    
    /**
     * Cria uma nova conta.
     * * @param accountId ID da conta (opcional, pode ser null para gerar automaticamente)
     * @param ownerName Nome do titular da conta
     * @param initialBalance Saldo inicial da conta
     * @return Conta criada ou null se falhar
     */
    public synchronized Account createAccount(String accountId, String ownerName, double initialBalance) {
        try {
            Account account;
            String finalAccountId = accountId;

            if (finalAccountId == null || finalAccountId.trim().isEmpty()) {
                // Gera um ID se não for fornecido (usando o nome do proprietário como base para o ID)
                // Esta lógica de geração de ID pode precisar ser mais robusta para evitar colisões.
                // Por agora, usa o ownerName como ID se accountId for nulo.
                // Em um sistema real, Account teria um construtor que gera um ID único.
                // Para este exemplo, vamos assumir que o 'login' (que é o ownerName) é o ID da conta.
                finalAccountId = ownerName; 
            }
            
            if (accounts.containsKey(finalAccountId)) {
                logger.warning("Tentativa de criar conta com ID (login) já existente: " + finalAccountId);
                return null; 
            }
            
            // Se o construtor Account(ownerName, initialBalance) define a senha/ID automaticamente:
            account = new Account(ownerName, initialBalance); // Este construtor deve definir o ID/senha.
                                                              // Se ele usa ownerName como ID, ok.
                                                              // Se ele gera um ID único, melhor ainda.
            
            // Se precisamos usar o finalAccountId explicitamente:
            // account = new Account(finalAccountId, ownerName, initialBalance); // Se Account tem construtor (id, name, balance)

            // Adiciona à lista de contas usando o ID/login da conta.
            // A chave do mapa 'accounts' deve ser o identificador único da conta.
            // Se Account.getSenha() retorna o ID único (como no seu código original):
            if (accounts.containsKey(account.getSenha())) {
                 logger.warning("Conta com ID (senha) '" + account.getSenha() + "' já existe após criação. Conflito de ID.");
                 return null; // Evita sobrescrever
            }
            accounts.put(account.getSenha(), account);
            logger.info("Conta criada: ID/Senha=" + account.getSenha() + " - Titular: " + account.getLogin() + " (" + ownerName + ")");
            
            return account;
        } catch (Exception e) {
            logger.log(Level.SEVERE, "Erro ao criar conta: " + e.getMessage(), e);
            return null;
        }
    }
    
    /**
     * Adiciona uma conta existente ao banco.
     * * @param account Conta a ser adicionada
     * @return true se a conta foi adicionada com sucesso, false caso contrário
     */
    public synchronized boolean addAccount(Account account) {
        try {
            if (account == null || account.getLogin() == null || account.getLogin().trim().isEmpty()) {
                logger.warning("Tentativa de adicionar conta nula ou com login inválido.");
                return false;
            }
            
            if (accounts.containsKey(account.getLogin())) {
                logger.warning("Tentativa de adicionar conta com login já existente: " + account.getSenha());
                return false;
            }
            
            accounts.put(account.getLogin(), account);
            logger.info("Conta adicionada: " + account.getLogin());
            
            return true;
        } catch (Exception e) {
            logger.log(Level.SEVERE, "Erro ao adicionar conta no AlphaBank: " + e.getMessage(), e);
            return false;
        }
    }
    
    /**
     * Obtém uma conta pelo ID.
     * * @param accountId ID da conta
     * @return Conta encontrada ou null se não existir
     */
    public Account getAccount(String accountId) {
        if (accountId == null) return null;
        return accounts.get(accountId);
    }
    
    /**
     * Realiza um depósito em uma conta.
     * * @param accountId ID da conta
     * @param amount Valor a ser depositado
     * @param description Descrição da transação
     * @return true se o depósito foi realizado com sucesso, false caso contrário
     */
    public synchronized boolean deposit(String accountId, double amount, String description) {
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
            logger.log(Level.SEVERE, "Erro ao realizar depósito: " + e.getMessage(), e);
            return false;
        }
    }
    
    /**
     * Realiza um saque em uma conta.
     * * @param accountId ID da conta
     * @param amount Valor a ser sacado
     * @param description Descrição da transação
     * @return true se o saque foi realizado com sucesso, false caso contrário
     */
    public synchronized boolean withdraw(String accountId, double amount, String description) {
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
            logger.log(Level.SEVERE, "Erro ao realizar saque: " + e.getMessage(), e);
            return false;
        }
    }
    
    /**
     * Realiza uma transferência entre contas.
     * * @param sourceAccountId ID da conta de origem
     * @param destinationAccountId ID da conta de destino
     * @param amount Valor a ser transferido
     * @param description Descrição da transação
     * @return true se a transferência foi realizada com sucesso, false caso contrário
     */
    public synchronized boolean transfer(String sourceAccountId, String destinationAccountId, double amount, String description) {
        try {
            Account sourceAccount = accounts.get(sourceAccountId);
            Account destinationAccount = accounts.get(destinationAccountId);
            
            if (sourceAccount == null) {
                logger.warning("Transferência falhou: Conta de origem '" + sourceAccountId + "' inexistente.");
                return false;
            }
            
            if (destinationAccount == null) {
                logger.warning("Transferência falhou: Conta de destino '" + destinationAccountId + "' inexistente.");
                return false;
            }
            
            if (!sourceAccount.isActive()) {
                logger.warning("Transferência falhou: Conta de origem '" + sourceAccountId + "' inativa.");
                return false;
            }

            if (!destinationAccount.isActive()) {
                logger.warning("Transferência falhou: Conta de destino '" + destinationAccountId + "' inativa.");
                return false;
            }
            
            if (amount <= 0) {
                logger.warning("Transferência falhou: Valor inválido: " + amount);
                return false;
            }
            
            if (sourceAccount.getBalance() < amount) {
                logger.warning("Transferência falhou: Saldo insuficiente na conta de origem '" + sourceAccountId + 
                               "'. Saldo: " + sourceAccount.getBalance() + ", Valor: " + amount);
                return false;
            }
            
            // Realiza a operação de forma atômica (embora aqui seja simplificado)
            sourceAccount.withdraw(amount, "Transferência para " + destinationAccountId + ": " + description);
            destinationAccount.deposit(amount, "Transferência de " + sourceAccountId + ": " + description);
            
            logger.info("Transferência realizada: " + sourceAccountId + " -> " + destinationAccountId + 
                        " - Valor: " + amount + " - Descrição: " + description);
            
            return true;
        } catch (Exception e) {
            logger.log(Level.SEVERE, "Erro GERAL ao realizar transferência: " + e.getMessage(), e);
            return false;
        }
    }
    
    /**
     * Ativa uma conta.
     * * @param accountId ID da conta
     * @return true se a conta foi ativada com sucesso, false caso contrário
     */
    public synchronized boolean activateAccount(String accountId) {
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
            logger.log(Level.SEVERE, "Erro ao ativar conta: " + e.getMessage(), e);
            return false;
        }
    }
    
    /**
     * Desativa uma conta.
     * * @param accountId ID da conta
     * @return true se a conta foi desativada com sucesso, false caso contrário
     */
    public synchronized boolean deactivateAccount(String accountId) {
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
            logger.log(Level.SEVERE, "Erro ao desativar conta: " + e.getMessage(), e);
            return false;
        }
    }
    
    /**
     * Para o servidor.
     */
    public void stopServer() {
        if (!running.getAndSet(false)) { // Define running como false e verifica o valor anterior
            logger.warning("Servidor AlphaBank já estava parado ou em processo de parada.");
            return;
        }
        
        logger.info("Parando servidor do AlphaBank...");
        
        try {
            // Fecha todas as conexões com clientes
            // É importante iterar sobre uma cópia para evitar ConcurrentModificationException se handleClientConnection modificar clientSockets
            List<String> clientIds = new ArrayList<>(clientSockets.keySet());
            for (String clientId : clientIds) {
                try {
                    Socket socket = clientSockets.remove(clientId);
                    if (socket != null && !socket.isClosed()) {
                        socket.close();
                    }
                    ObjectOutputStream oos = clientOutputStreams.remove(clientId);
                    if (oos != null) oos.close();
                    ObjectInputStream ois = clientInputStreams.remove(clientId);
                    if (ois != null) ois.close();
                    clientDesSessionEncryptors.remove(clientId);
                } catch (IOException e) {
                    logger.log(Level.WARNING, "Erro ao fechar socket do cliente " + clientId + ": " + e.getMessage());
                }
            }
            clientSockets.clear();
            clientOutputStreams.clear();
            clientInputStreams.clear();
            clientDesSessionEncryptors.clear();
            
            // Encerra o executor de threads
            if (clientExecutorService != null) {
                clientExecutorService.shutdown();
                try {
                    if (!clientExecutorService.awaitTermination(5, TimeUnit.SECONDS)) {
                        clientExecutorService.shutdownNow();
                    }
                } catch (InterruptedException e) {
                    clientExecutorService.shutdownNow();
                    Thread.currentThread().interrupt();
                }
            }
            
            // Fecha o socket do servidor
            if (serverSocket != null && !serverSocket.isClosed()) {
                serverSocket.close();
                logger.info("ServerSocket do AlphaBank fechado.");
            }
            
            logger.info("Servidor do AlphaBank parado com sucesso.");
        } catch (IOException e) {
            logger.log(Level.SEVERE, "Erro ao parar servidor AlphaBank: " + e.getMessage(), e);
        }
    }
    
    /**
     * Obtém a lista de contas.
     * * @return Lista de contas
     */
    public List<Account> getAccounts() {
        return new ArrayList<>(accounts.values());
    }
    
    /**
     * Obtém o número de contas.
     * * @return Número de contas
     */
    public int getAccountCount() {
        return accounts.size();
    }
    
    /**
     * Obtém o número de clientes conectados.
     * * @return Número de clientes conectados
     */
    public int getConnectedClientCount() {
        return clientSockets.size();
    }
    
    /**
     * Verifica se o servidor está em execução.
     * * @return true se o servidor está em execução, false caso contrário
     */
    public boolean isRunning() {
        return running.get();
    }
    
    /**
     * Obtém a porta do servidor.
     * * @return Porta do servidor
     */
    public int getServerPort() {
        return serverPort;
    }
    
    /**
     * Obtém o ID do banco.
     * * @return ID do banco
     */
    public String getBankId() {
        return bankId;
    }
}
