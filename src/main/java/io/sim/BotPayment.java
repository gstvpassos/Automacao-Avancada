package io.sim;

import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.net.Socket;
import java.security.PublicKey;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import io.sim.utils.JsonUtil;

/**
 * Classe responsável por automatizar pagamentos entre contas.
 * Funciona como um bot que realiza transferências periódicas ou sob demanda.
 */
public class BotPayment extends Thread {
    
    // Identificador único do bot
    private String botId;
    
    // Conta de origem para os pagamentos
    private Account sourceAccount;
    
    // Conexão com o servidor AlphaBank
    private Socket bankConnection;
    private ObjectOutputStream out;
    private ObjectInputStream in;
    
    // Controle de execução
    private boolean running;
    private ScheduledExecutorService scheduler;
    
    // Configurações
    private long paymentInterval; // Intervalo entre pagamentos automáticos em milissegundos
    private boolean autoPaymentEnabled; // Indica se o pagamento automático está habilitado
    
    // Histórico de pagamentos
    private List<PaymentRecord> paymentHistory;
    
    // Utilitário de criptografia
    private EncriptaDecriptaDES sessionEncryptor;
    
    /**
     * Construtor principal do BotPayment.
     * 
     * @param sourceAccount Conta de origem para os pagamentos
     * @param bankHost Host do servidor AlphaBank
     * @param bankPort Porta do servidor AlphaBank
     * @param paymentInterval Intervalo entre pagamentos automáticos (em milissegundos)
     * @param autoPaymentEnabled Indica se o pagamento automático está habilitado
     */
    public BotPayment(Account sourceAccount, String bankHost, int bankPort, 
                     long paymentInterval, boolean autoPaymentEnabled) {
        this.botId = "BOT_" + UUID.randomUUID().toString().substring(0, 8);
        this.sourceAccount = sourceAccount;
        this.paymentInterval = paymentInterval;
        this.autoPaymentEnabled = autoPaymentEnabled;
        this.paymentHistory = new ArrayList<>();
        this.running = false;
        
        try {
            // Inicializa o encriptador
            this.sessionEncryptor = new EncriptaDecriptaDES();
            
            // Conecta ao servidor AlphaBank
            connectToBank(bankHost, bankPort);
        } catch (Exception e) {
            System.err.println("[BotPayment " + botId + "] Erro ao inicializar: " + e.getMessage());
        }
    }
    
    /**
     * Construtor simplificado do BotPayment.
     * 
     * @param sourceAccount Conta de origem para os pagamentos
     * @param bankHost Host do servidor AlphaBank
     * @param bankPort Porta do servidor AlphaBank
     */
    public BotPayment(Account sourceAccount, String bankHost, int bankPort) {
        this(sourceAccount, bankHost, bankPort, 60000, false); // Padrão: 1 minuto, sem pagamento automático
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
            System.out.println("[BotPayment " + botId + "] Iniciando conexão com AlphaBank em " + host + ":" + port);
            this.bankConnection = new Socket(host, port);
            // Considere adicionar um timeout para operações de socket se necessário
            // this.bankConnection.setSoTimeout(10000); // 10 segundos

            System.out.println("[BotPayment " + botId + "] Socket criado. Criando streams...");
            this.out = new ObjectOutputStream(bankConnection.getOutputStream());
            this.out.flush(); // Flush antes de criar ObjectInputStream é uma boa prática
            this.in = new ObjectInputStream(bankConnection.getInputStream());
            System.out.println("[BotPayment " + botId + "] Streams de entrada e saída criadas.");

            // 1. ENVIAR ID DO CLIENTE (botId) PARA O SERVIDOR
            System.out.println("[BotPayment " + botId + "] Enviando ID do cliente: " + this.botId);
            out.writeObject(this.botId);
            out.flush();

            // 2. RECEBER CHAVE PÚBLICA RSA DO SERVIDOR (em Base64)
            String serverRsaPublicKeyBase64 = (String) in.readObject();
            System.out.println("[BotPayment " + botId + "] Chave pública RSA do servidor recebida.");
            // Você precisará da classe EncriptaDecriptaRSA e seu método estático aqui
            PublicKey serverRsaPublicKey = EncriptaDecriptaRSA.getPublicKeyFromBase64(serverRsaPublicKeyBase64);

            // 3. GERAR UMA CHAVE DE SESSÃO DES ALEATÓRIA
            EncriptaDecriptaDES desKeyGenerator = new EncriptaDecriptaDES(); // Construtor padrão gera uma nova chave DES
            byte[] desSessionKeyBytes = desKeyGenerator.getChaveDESBytes();
            
            // Inicializa o sessionEncryptor DESTE BotPayment com a chave de sessão gerada
            this.sessionEncryptor = new EncriptaDecriptaDES(desSessionKeyBytes);
            System.out.println("[BotPayment " + botId + "] Chave DES de sessão gerada.");

            // 4. CRIPTOGRAFAR A CHAVE DE SESSÃO DES COM A CHAVE PÚBLICA RSA DO SERVIDOR
            byte[] encryptedDesSessionKey = EncriptaDecriptaRSA.criptografarComPublicKey(desSessionKeyBytes, serverRsaPublicKey);

            // 5. ENVIAR A CHAVE DE SESSÃO DES (CRIPTOGRAFADA COM RSA) PARA O SERVIDOR
            out.writeObject(encryptedDesSessionKey);
            out.flush();
            System.out.println("[BotPayment " + botId + "] Chave DES de sessão criptografada enviada ao servidor.");

            // 6. AGUARDAR CONFIRMAÇÃO DE CONEXÃO SEGURA DO SERVIDOR (CRIPTOGRAFADA COM A CHAVE DES DE SESSÃO)
            // O servidor deve enviar uma String Base64 criptografada
            String encryptedConfirmation = (String) in.readObject();
            String confirmationMessage = this.sessionEncryptor.descriptografar(encryptedConfirmation);

            if (!"CONNECTED_SECURELY".equals(confirmationMessage)) {
                throw new IOException("Falha ao estabelecer conexão segura com o servidor AlphaBank. Resposta de confirmação inválida: " + confirmationMessage);
            }
            System.out.println("[BotPayment " + botId + "] Conexão segura estabelecida com o servidor AlphaBank.");

            // Agora que a conexão segura está estabelecida, proceda com a autenticação do BotPayment
            sendAuthenticationRequest(); // Este método DEVE usar this.sessionEncryptor internamente
            
            // O log "[BotPayment " + botId + "] Conectado ao servidor AlphaBank" pode ser movido para após a autenticação bem-sucedida,
            // ou pode ser mantido aqui se "conectado" significa que a negociação de chave foi bem-sucedida.
            // Por clareza, é melhor considerar "conectado e autenticado" após sendAuthenticationRequest.

        } catch (IOException e) {
            System.err.println("[BotPayment " + botId + "] Erro de IO durante conexão/negociação de chave com AlphaBank: " + e.getMessage());
            throw e; // Re-throw para que o construtor possa lidar com a falha
        } catch (ClassNotFoundException e) {
            System.err.println("[BotPayment " + botId + "] Erro de Classe Não Encontrada durante negociação de chave: " + e.getMessage());
            throw new IOException("Erro de comunicação com o servidor (classe não encontrada)", e);
        } catch (Exception e) { // Captura outras exceções (ex: de criptografia, NoSuchAlgorithmException)
            System.err.println("[BotPayment " + botId + "] Exceção geral durante conexão/negociação de chave: " + e.getMessage());
            // e.printStackTrace(); // Descomente para depuração detalhada da stack trace
            throw new IOException("Erro na configuração da criptografia ou negociação de chave: " + e.getMessage(), e);
        }
    }
    
    /**
     * Envia solicitação de autenticação para o servidor AlphaBank.
     * 
     * @throws IOException Se ocorrer um erro de comunicação
     */
    private void sendAuthenticationRequest() throws IOException {
        if (this.sessionEncryptor == null) {
            System.err.println("[BotPayment " + botId + "] Conexão segura não estabelecida. Impossível autenticar.");
            throw new IOException("Conexão segura não estabelecida. Impossível autenticar.");
        }
        try {
            // Crie uma instância do seu objeto AuthRequest (agora com o campo 'action')
            AuthRequest authDataObject = new AuthRequest(
                    "AUTHENTICATE",                 // action
                    sourceAccount.getLogin(),       // login
                    sourceAccount.getSenha(),       // password
                    "BOT_PAYMENT"                   // clientType
            );

            // Use JsonUtil para converter o objeto para uma string JSON
            String authRequestString = JsonUtil.toJson(authDataObject);
            
            System.out.println("[BotPayment " + botId + "] Enviando requisição de autenticação JSON (via JsonUtil): " + authRequestString);

            // Criptografa a requisição JSON usando a chave de sessão DES
            String encryptedRequest = this.sessionEncryptor.criptografar(authRequestString);

            // Envia a requisição criptografada para o servidor
            out.writeObject(encryptedRequest);
            out.flush();

            // Aguarda a resposta do servidor
            String encryptedResponse = (String) in.readObject();
            // Descriptografa a resposta usando a chave de sessão DES
            String responseString = this.sessionEncryptor.descriptografar(encryptedResponse);

            System.out.println("[BotPayment " + botId + "] Resposta de autenticação JSON recebida: " + responseString);

            // Processa a resposta JSON usando JsonUtil.jsonToMap
            Map<String, Object> responseMap = JsonUtil.jsonToMap(responseString);

            if (responseMap == null) {
                // Isso pode acontecer se responseString for um JSON inválido e JsonUtil.jsonToMap retornar null.
                System.err.println("[BotPayment " + botId + "] Erro ao parsear a resposta JSON do servidor. Resposta recebida: " + responseString);
                throw new IOException("Erro ao parsear a resposta JSON do servidor.");
            }

            // Obtém o status da resposta do mapa.
            // É uma boa prática verificar se a chave existe e se o tipo é o esperado.
            Object statusObject = responseMap.get("status");
            String status = null;
            if (statusObject instanceof String) {
                status = (String) statusObject;
            }

            if (!"success".equals(status)) {
                // Tenta obter uma mensagem de erro mais detalhada do servidor
                Object messageObject = responseMap.get("message");
                String serverMessage = "Nenhuma mensagem de erro específica fornecida pelo servidor.";
                if (messageObject instanceof String) {
                    serverMessage = (String) messageObject;
                }
                String errorMessage = "Status: " + (status != null ? status : "desconhecido") + ". Mensagem do servidor: " + serverMessage;
                throw new IOException("Falha na autenticação: " + errorMessage);
            }
            
            System.out.println("[BotPayment " + botId + "] Autenticação bem-sucedida.");

        } catch (Exception e) { // Captura outras exceções (criptografia, IO, ClassNotFoundException se in.readObject() falhar)
            System.err.println("[BotPayment " + botId + "] Erro durante a autenticação (pós-negociação de chave): " + e.getMessage());
            // e.printStackTrace(); // Descomente para depuração detalhada, se necessário
            throw new IOException("Erro na autenticação: " + e.getMessage(), e);
        }
    }

    /**
     * Método principal da thread.
     * Inicia o agendador de pagamentos automáticos se habilitado.
     */
    @Override
    public void run() {
        this.running = true;
        
        // Inicializa o agendador se o pagamento automático estiver habilitado
        if (autoPaymentEnabled) {
            startAutoPaymentScheduler();
        }
        
        // Loop principal para manter a thread ativa
        while (running) {
            try {
                // Verifica se há mensagens do servidor
                if (bankConnection.getInputStream().available() > 0) {
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
                System.err.println("[BotPayment " + botId + "] Erro no loop principal: " + e.getMessage());
                
                // Tenta reconectar em caso de erro de conexão
                if (e instanceof IOException) {
                    try {
                        reconnect();
                    } catch (Exception reconnectError) {
                        System.err.println("[BotPayment " + botId + "] Falha ao reconectar: " + reconnectError.getMessage());
                        running = false;
                        break;
                    }
                }
            }
        }
        
        // Limpa recursos ao encerrar
        cleanup();
    }
    
    /**
     * Inicia o agendador de pagamentos automáticos.
     */
    private void startAutoPaymentScheduler() {
        this.scheduler = Executors.newScheduledThreadPool(1);
        
        // Agenda a tarefa de pagamento automático
        scheduler.scheduleAtFixedRate(() -> {
            try {
                // Aqui seria implementada a lógica para determinar pagamentos automáticos
                // Por exemplo, verificar contas pendentes e realizar pagamentos
                System.out.println("[BotPayment " + botId + "] Verificando pagamentos automáticos...");
                
                // Exemplo: poderia buscar pagamentos pendentes de um serviço
                // List<PaymentRequest> pendingPayments = fetchPendingPayments();
                // for (PaymentRequest payment : pendingPayments) {
                //     processPayment(payment.getDestinationAccount(), payment.getAmount(), payment.getDescription());
                // }
            } catch (Exception e) {
                System.err.println("[BotPayment " + botId + "] Erro no pagamento automático: " + e.getMessage());
            }
        }, 0, paymentInterval, TimeUnit.MILLISECONDS);
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
            
            System.out.println("[BotPayment " + botId + "] Mensagem recebida: " + message);
            
            // Aqui seria implementada a lógica para processar diferentes tipos de mensagens
            // Por exemplo, confirmações de pagamento, notificações, etc.
            
            // Exemplo:
            // if (message.contains("\"type\":\"payment_confirmation\"")) {
            //     PaymentConfirmation confirmation = JsonUtil.fromJson(message, PaymentConfirmation.class);
            //     updatePaymentStatus(confirmation);
            // }
        } catch (Exception e) {
            System.err.println("[BotPayment " + botId + "] Erro ao processar mensagem: " + e.getMessage());
            throw e;
        }
    }
    
    /**
     * Realiza um pagamento para uma conta de destino.
     * 
     * @param destinationAccount Conta de destino
     * @param amount Valor a ser transferido
     * @param description Descrição do pagamento
     * @return ID do pagamento se bem-sucedido, null caso contrário
     */
    public String processPayment(Account destinationAccount, double amount, String description) {
        if (!running || bankConnection == null || bankConnection.isClosed()) {
            System.err.println("[BotPayment " + botId + "] Não é possível processar pagamento: conexão fechada");
            return null;
        }
        
        if (amount <= 0) {
            System.err.println("[BotPayment " + botId + "] Valor de pagamento inválido: " + amount);
            return null;
        }
        
        try {
            // Gera ID único para o pagamento
            String paymentId = "PAY_" + UUID.randomUUID().toString().substring(0, 8);
            
            // Cria objeto de pagamento
            PaymentRequest paymentRequest = new PaymentRequest(
                    paymentId,
                    sourceAccount.getLogin(),
                    destinationAccount.getLogin(),
                    amount,
                    description,
                    System.currentTimeMillis()
            );
            
            // Converte para JSON e criptografa
            String paymentJson = JsonUtil.toJson(paymentRequest);
            String encryptedPayment = sessionEncryptor.criptografar(paymentJson);
            
            // Envia para o servidor
            out.writeObject(encryptedPayment);
            out.flush();
            
            // Aguarda confirmação (poderia ser assíncrono em uma implementação mais avançada)
            String encryptedResponse = (String) in.readObject();
            String response = sessionEncryptor.descriptografar(encryptedResponse);
            
            // Verifica se o pagamento foi bem-sucedido
            if (response.contains("\"status\":\"success\"")) {
                // Registra o pagamento no histórico
                PaymentRecord record = new PaymentRecord(
                        paymentId,
                        sourceAccount.getLogin(),
                        destinationAccount.getLogin(),
                        amount,
                        description,
                        System.currentTimeMillis(),
                        true,
                        null
                );
                paymentHistory.add(record);
                
                System.out.println("[BotPayment " + botId + "] Pagamento processado com sucesso: " + paymentId);
                return paymentId;
            } else {
                // Registra a falha no histórico
                String errorMessage = response.contains("\"message\":") 
                        ? response.split("\"message\":\"")[1].split("\"")[0] 
                        : "Erro desconhecido";
                
                PaymentRecord record = new PaymentRecord(
                        paymentId,
                        sourceAccount.getLogin(),
                        destinationAccount.getLogin(),
                        amount,
                        description,
                        System.currentTimeMillis(),
                        false,
                        errorMessage
                );
                paymentHistory.add(record);
                
                System.err.println("[BotPayment " + botId + "] Falha no pagamento: " + errorMessage);
                return null;
            }
        } catch (Exception e) {
            System.err.println("[BotPayment " + botId + "] Erro ao processar pagamento: " + e.getMessage());
            return null;
        }
    }
    
    /**
     * Tenta reconectar ao servidor AlphaBank.
     * 
     * @throws Exception Se ocorrer um erro na reconexão
     */
    private void reconnect() throws Exception {
        System.out.println("[BotPayment " + botId + "] Tentando reconectar ao servidor AlphaBank...");
        
        // Fecha conexão atual se existir
        if (bankConnection != null && !bankConnection.isClosed()) {
            try {
                bankConnection.close();
            } catch (IOException e) {
                // Ignora erros ao fechar
            }
        }
        
        // Tenta reconectar (assumindo que os parâmetros de conexão estão armazenados)
        // Na implementação real, seria necessário armazenar host e porta
        String host = bankConnection.getInetAddress().getHostName();
        int port = bankConnection.getPort();
        
        // Reconecta
        connectToBank(host, port);
    }
    
    /**
     * Limpa recursos ao encerrar o bot.
     */
    private void cleanup() {
        // Para o agendador se estiver ativo
        if (scheduler != null && !scheduler.isShutdown()) {
            scheduler.shutdown();
            try {
                scheduler.awaitTermination(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                scheduler.shutdownNow();
            }
        }
        
        // Fecha conexão com o banco
        try {
            if (out != null) out.close();
            if (in != null) in.close();
            if (bankConnection != null && !bankConnection.isClosed()) {
                bankConnection.close();
            }
        } catch (IOException e) {
            System.err.println("[BotPayment " + botId + "] Erro ao fechar conexões: " + e.getMessage());
        }
        
        System.out.println("[BotPayment " + botId + "] Finalizado");
    }
    
    /**
     * Para a execução do bot.
     */
    public void stopBot() {
        this.running = false;
        this.interrupt();
    }
    
    /**
     * Habilita ou desabilita o pagamento automático.
     * 
     * @param enabled true para habilitar, false para desabilitar
     */
    public void setAutoPaymentEnabled(boolean enabled) {
        this.autoPaymentEnabled = enabled;
        
        if (enabled && running && (scheduler == null || scheduler.isShutdown())) {
            // Inicia o agendador se estiver habilitado e não estiver ativo
            startAutoPaymentScheduler();
        } else if (!enabled && scheduler != null && !scheduler.isShutdown()) {
            // Para o agendador se estiver desabilitado e estiver ativo
            scheduler.shutdown();
        }
    }
    
    /**
     * Define o intervalo entre pagamentos automáticos.
     * 
     * @param interval Intervalo em milissegundos
     */
    public void setPaymentInterval(long interval) {
        this.paymentInterval = interval;
        
        // Reinicia o agendador se estiver ativo
        if (autoPaymentEnabled && running && scheduler != null && !scheduler.isShutdown()) {
            scheduler.shutdown();
            try {
                scheduler.awaitTermination(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                scheduler.shutdownNow();
            }
            
            startAutoPaymentScheduler();
        }
    }
    
    /**
     * Obtém o histórico de pagamentos.
     * 
     * @return Lista de registros de pagamento
     */
    public List<PaymentRecord> getPaymentHistory() {
        return new ArrayList<>(paymentHistory);
    }
    
    /**
     * Obtém o ID do bot.
     * 
     * @return ID do bot
     */
    public String getBotId() {
        return botId;
    }
    
    /**
     * Obtém a conta de origem.
     * 
     * @return Conta de origem
     */
    public Account getSourceAccount() {
        return sourceAccount;
    }
    
    /**
     * Verifica se o bot está em execução.
     * 
     * @return true se estiver em execução, false caso contrário
     */
    public boolean isRunning() {
        return running;
    }
    
    /**
     * Verifica se o pagamento automático está habilitado.
     * 
     * @return true se estiver habilitado, false caso contrário
     */
    public boolean isAutoPaymentEnabled() {
        return autoPaymentEnabled;
    }
    
    /**
     * Obtém o intervalo entre pagamentos automáticos.
     * 
     * @return Intervalo em milissegundos
     */
    public long getPaymentInterval() {
        return paymentInterval;
    }
    
    /**
     * Classe interna para representar uma solicitação de autenticação.
     */
    private static class AuthRequest {
    private String action;    // NOVO CAMPO
    private String login;     // Mantendo "login" para consistência com o Driver
    private String password;
    private String clientType;

    public AuthRequest(String action, String login, String password, String clientType) {
        this.action = action;
        this.login = login;
        this.password = password;
        this.clientType = clientType;
    }

    // Getters são importantes se o JsonUtil os utilizar (embora o seu use reflexão direta nos campos)
    // Mesmo assim, é uma boa prática para POJOs.
    public String getAction() {
        return action;
    }

    public String getLogin() {
        return login;
    }

    public String getPassword() {
        return password;
    }

    public String getClientType() {
        return clientType;
    }

    // Setters (opcionais, dependendo se você precisa modificar o objeto após a criação)
    public void setAction(String action) {
        this.action = action;
    }

    public void setLogin(String login) {
        this.login = login;
    }

    public void setPassword(String password) {
        this.password = password;
    }

    public void setClientType(String clientType) {
        this.clientType = clientType;
    }
}
    
    /**
     * Classe interna para representar uma solicitação de pagamento.
     */
    private static class PaymentRequest {
        private String paymentId;
        private String sourceAccount;
        private String destinationAccount;
        private double amount;
        private String description;
        private long timestamp;
        
        public PaymentRequest(String paymentId, String sourceAccount, String destinationAccount, 
                             double amount, String description, long timestamp) {
            this.paymentId = paymentId;
            this.sourceAccount = sourceAccount;
            this.destinationAccount = destinationAccount;
            this.amount = amount;
            this.description = description;
            this.timestamp = timestamp;
        }
        
        // Getters e setters omitidos para brevidade
    }
    
    /**
     * Classe interna para representar um registro de pagamento.
     */
    public static class PaymentRecord {
        private String paymentId;
        private String sourceAccount;
        private String destinationAccount;
        private double amount;
        private String description;
        private long timestamp;
        private boolean successful;
        private String errorMessage;
        
        public PaymentRecord(String paymentId, String sourceAccount, String destinationAccount,
                            double amount, String description, long timestamp,
                            boolean successful, String errorMessage) {
            this.paymentId = paymentId;
            this.sourceAccount = sourceAccount;
            this.destinationAccount = destinationAccount;
            this.amount = amount;
            this.description = description;
            this.timestamp = timestamp;
            this.successful = successful;
            this.errorMessage = errorMessage;
        }
        
        public String getPaymentId() {
            return paymentId;
        }
        
        public String getSourceAccount() {
            return sourceAccount;
        }
        
        public String getDestinationAccount() {
            return destinationAccount;
        }
        
        public double getAmount() {
            return amount;
        }
        
        public String getDescription() {
            return description;
        }
        
        public long getTimestamp() {
            return timestamp;
        }
        
        public boolean isSuccessful() {
            return successful;
        }
        
        public String getErrorMessage() {
            return errorMessage;
        }
    }
}
