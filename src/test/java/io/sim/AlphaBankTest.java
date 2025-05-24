// package io.sim;

// import junit.framework.TestCase;
// import java.util.List;

// import org.junit.Test;

// /**
//  * Testes unitários para a classe AlphaBank.
//  * Utiliza JUnit TestCase para validar o funcionamento do AlphaBank.
//  */
// public class AlphaBankTest extends TestCase {
    
//     private AlphaBank alphaBank;
//     private Account testAccount;
//     private Account destinationAccount;
    
//     /**
//      * Configuração inicial para os testes.
//      */
//     @Override
//     protected void setUp() throws Exception {
//         super.setUp();
        
//         // Cria uma instância do AlphaBank para os testes
//         alphaBank = new AlphaBank(9090);
        
//         // Cria contas para os testes
//         testAccount = new Account("ACC001", "Teste da Silva", 1000.0);
//         destinationAccount = new Account("ACC002", "João Recebedor", 500.0);
//     }
    
//     /**
//      * Limpeza após os testes.
//      */
//     @Override
//     protected void tearDown() throws Exception {
//         super.tearDown();
        
//         // Para o servidor do AlphaBank se estiver em execução
//         if (alphaBank != null && alphaBank.isRunning()) {
//             alphaBank.stopServer();
//         }
//     }
    
//     /**
//      * Testa a criação do AlphaBank.
//      */
//     public void testAlphaBankCreation() {
//         assertNotNull("AlphaBank não deve ser nulo", alphaBank);
//         assertEquals("Porta do servidor deve ser 9090", 9090, alphaBank.getServerPort());
//         assertEquals("ID do banco deve ser ALPHABANK", "ALPHABANK", alphaBank.getBankId());
//         assertFalse("AlphaBank não deve estar em execução inicialmente", alphaBank.isRunning());
//     }
    
//     /**
//      * Testa a adição de contas ao AlphaBank.
//      */
//     public void testAddAccount() {
//         boolean result = alphaBank.addAccount(testAccount);
        
//         assertTrue("Adição de conta deve ser bem-sucedida", result);
//         assertNotNull("Conta deve ser encontrada após adição", alphaBank.getAccount("ACC001"));
//         assertEquals("Conta encontrada deve ser a mesma que foi adicionada", testAccount, alphaBank.getAccount("ACC001"));
        
//         // Testa adição de conta com ID duplicado
//         Account duplicateAccount = new Account("ACC001", "Duplicado", 2000.0);
//         result = alphaBank.addAccount(duplicateAccount);
        
//         assertFalse("Adição de conta com ID duplicado deve falhar", result);
        
//         // Testa adição de conta nula
//         result = alphaBank.addAccount(null);
        
//         assertFalse("Adição de conta nula deve falhar", result);
//     }
    
//     /**
//      * Testa a criação de contas pelo AlphaBank.
//      */
//     public void testCreateAccount() {
//         Account account = alphaBank.createAccount("ACC003", "Criado pelo Banco", 1500.0);
        
//         assertNotNull("Conta criada não deve ser nula", account);
//         assertEquals("ID da conta criada deve ser ACC003", "ACC003", account.getSenha());
//         assertEquals("Nome do titular da conta criada deve ser Criado pelo Banco", "Criado pelo Banco", account.getLogin());
//         assertEquals("Saldo inicial da conta criada deve ser 1500.0", 1500.0, account.getBalance());
        
//         // Testa criação de conta com ID já existente
//         alphaBank.addAccount(testAccount);
//         Account duplicateAccount = alphaBank.createAccount("ACC001", "Duplicado", 2000.0);
        
//         assertNull("Criação de conta com ID duplicado deve falhar", duplicateAccount);
        
//         // Testa criação de conta com ID gerado automaticamente
//         Account autoIdAccount = alphaBank.createAccount(null, "Auto Gerado", 2000.0);
        
//         assertNotNull("Conta com ID gerado automaticamente não deve ser nula", autoIdAccount);
//         assertNotNull("ID da conta com ID gerado automaticamente não deve ser nulo", autoIdAccount.getSenha());
//         assertTrue("ID da conta com ID gerado automaticamente deve ter comprimento maior que 0", autoIdAccount.getSenha().length() > 0);
//     }
    
//     /**
//      * Testa a remoção de contas do AlphaBank.
//      */
//     @Test
//     public void testRemoveAccount() {
//         alphaBank.addAccount(testAccount);
        
//         boolean result = alphaBank.removeAccount("ACC001");
        
//         assertTrue("Remoção de conta deve ser bem-sucedida", result);
//         assertNull("Conta não deve ser encontrada após remoção", alphaBank.getAccount("ACC001"));
        
//         // Testa remoção de conta inexistente
//         result = alphaBank.removeAccount("INEXISTENTE");
        
//         assertFalse("Remoção de conta inexistente deve falhar", result);
//     }
    
//     /**
//      * Testa o depósito em contas pelo AlphaBank.
//      */
//     public void testDeposit() {
//         alphaBank.addAccount(testAccount);
        
//         boolean result = alphaBank.deposit("ACC001", 500.0, "Depósito de teste");
        
//         assertTrue("Depósito deve ser bem-sucedido", result);
//         assertEquals("Saldo após depósito deve ser 1500.0", 1500.0, alphaBank.getAccount("ACC001").getBalance());
        
//         // Testa depósito em conta inexistente
//         result = alphaBank.deposit("INEXISTENTE", 100.0, "Depósito inválido");
        
//         assertFalse("Depósito em conta inexistente deve falhar", result);
        
//         // Testa depósito com valor negativo
//         result = alphaBank.deposit("ACC001", -100.0, "Depósito inválido");
        
//         assertFalse("Depósito com valor negativo deve falhar", result);
//         assertEquals("Saldo não deve mudar após depósito inválido", 1500.0, alphaBank.getAccount("ACC001").getBalance());
//     }
    
//     /**
//      * Testa o saque em contas pelo AlphaBank.
//      */
//     public void testWithdraw() {
//         alphaBank.addAccount(testAccount);
        
//         boolean result = alphaBank.withdraw("ACC001", 300.0, "Saque de teste");
        
//         assertTrue("Saque deve ser bem-sucedido", result);
//         assertEquals("Saldo após saque deve ser 700.0", 700.0, alphaBank.getAccount("ACC001").getBalance());
        
//         // Testa saque em conta inexistente
//         result = alphaBank.withdraw("INEXISTENTE", 100.0, "Saque inválido");
        
//         assertFalse("Saque em conta inexistente deve falhar", result);
        
//         // Testa saque com valor maior que o saldo
//         result = alphaBank.withdraw("ACC001", 1000.0, "Saque inválido");
        
//         assertFalse("Saque com valor maior que o saldo deve falhar", result);
//         assertEquals("Saldo não deve mudar após saque inválido", 700.0, alphaBank.getAccount("ACC001").getBalance());
//     }
    
//     /**
//      * Testa a transferência entre contas pelo AlphaBank.
//      */
//     public void testTransfer() {
//         alphaBank.addAccount(testAccount);
//         alphaBank.addAccount(destinationAccount);
        
//         boolean result = alphaBank.transfer("ACC001", "ACC002", 300.0, "Transferência de teste");
        
//         assertTrue("Transferência deve ser bem-sucedida", result);
//         assertEquals("Saldo da conta de origem após transferência deve ser 700.0", 700.0, alphaBank.getAccount("ACC001").getBalance());
//         assertEquals("Saldo da conta de destino após transferência deve ser 800.0", 800.0, alphaBank.getAccount("ACC002").getBalance());
        
//         // Testa transferência com conta de origem inexistente
//         result = alphaBank.transfer("INEXISTENTE", "ACC002", 100.0, "Transferência inválida");
        
//         assertFalse("Transferência com conta de origem inexistente deve falhar", result);
        
//         // Testa transferência com conta de destino inexistente
//         result = alphaBank.transfer("ACC001", "INEXISTENTE", 100.0, "Transferência inválida");
        
//         assertFalse("Transferência com conta de destino inexistente deve falhar", result);
        
//         // Testa transferência com valor maior que o saldo
//         result = alphaBank.transfer("ACC001", "ACC002", 1000.0, "Transferência inválida");
        
//         assertFalse("Transferência com valor maior que o saldo deve falhar", result);
//         assertEquals("Saldo da conta de origem não deve mudar após transferência inválida", 700.0, alphaBank.getAccount("ACC001").getBalance());
//         assertEquals("Saldo da conta de destino não deve mudar após transferência inválida", 800.0, alphaBank.getAccount("ACC002").getBalance());
//     }
    
//     /**
//      * Testa a ativação e desativação de contas pelo AlphaBank.
//      */
//     public void testActivateDeactivateAccount() {
//         alphaBank.addAccount(testAccount);
        
//         // Inicialmente a conta está ativa
//         assertTrue("Conta deve estar ativa inicialmente", alphaBank.getAccount("ACC001").isActive());
        
//         // Testa desativação
//         boolean result = alphaBank.deactivateAccount("ACC001");
        
//         assertTrue("Desativação deve ser bem-sucedida", result);
//         assertFalse("Conta deve estar inativa após desativação", alphaBank.getAccount("ACC001").isActive());
        
//         // Testa desativação de conta inexistente
//         result = alphaBank.deactivateAccount("INEXISTENTE");
        
//         assertFalse("Desativação de conta inexistente deve falhar", result);
        
//         // Testa ativação
//         result = alphaBank.activateAccount("ACC001");
        
//         assertTrue("Ativação deve ser bem-sucedida", result);
//         assertTrue("Conta deve estar ativa após ativação", alphaBank.getAccount("ACC001").isActive());
        
//         // Testa ativação de conta inexistente
//         result = alphaBank.activateAccount("INEXISTENTE");
        
//         assertFalse("Ativação de conta inexistente deve falhar", result);
//     }
    
//     /**
//      * Testa a obtenção de todas as contas do AlphaBank.
//      */
//     public void testGetAllAccounts() {
//         // Inicialmente não há contas
//         List<Account> accounts = alphaBank.getAllAccounts();
        
//         assertNotNull("Lista de contas não deve ser nula", accounts);
//         assertEquals("Lista de contas deve estar vazia inicialmente", 0, accounts.size());
        
//         // Adiciona algumas contas
//         alphaBank.addAccount(testAccount);
//         alphaBank.addAccount(destinationAccount);
        
//         // Obtém todas as contas
//         accounts = alphaBank.getAllAccounts();
        
//         assertEquals("Lista de contas deve conter 2 contas", 2, accounts.size());
//         assertTrue("Lista de contas deve conter a primeira conta", accounts.contains(testAccount));
//         assertTrue("Lista de contas deve conter a segunda conta", accounts.contains(destinationAccount));
//     }
    
//     /**
//      * Testa o ciclo de vida do servidor do AlphaBank.
//      */
//     @Test
//     public void testServerLifecycle() {
//         // Inicialmente o servidor não está em execução
//         assertFalse("Servidor não deve estar em execução inicialmente", alphaBank.isRunning());
        
//         // Inicia o servidor em uma thread separada
//         Thread thread = new Thread(alphaBank);
//         thread.start();
        
//         // Aguarda a inicialização do servidor
//         try {
//             Thread.sleep(1000);
//         } catch (InterruptedException e) {
//             e.printStackTrace();
//         }
        
//         // Verifica se o servidor está em execução
//         assertTrue("Servidor deve estar em execução após inicialização", alphaBank.isRunning());
        
//         // Para o servidor
//         alphaBank.stopServer();
        
//         // Aguarda o encerramento do servidor
//         try {
//             thread.join(1000);
            
//         } catch (InterruptedException e) {
//             assertFalse("Servidor não deve estar em execução após ser parado",alphaBank.isRunning());
//         }     
//     }
// }
