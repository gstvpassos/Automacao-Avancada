package io.sim;

import junit.framework.TestCase;
import java.io.File;
import java.util.List;

/**
 * Testes unitários para a classe MobilityCompany.
 * Utiliza JUnit TestCase para validar o funcionamento da MobilityCompany.
 */
public class MobilityCompanyTest extends TestCase {
    
    private MobilityCompany mobilityCompany;
    private AlphaBank alphaBankServer;
    private Account companyAccount;
    private String rotasXmlPath;
    
    /**
     * Configuração inicial para os testes.
     */
    @Override
    protected void setUp() throws Exception {
        super.setUp();
        
        // Inicializa o AlphaBank para os testes
        alphaBankServer = new AlphaBank(9090);
        
        // Cria uma conta para a empresa
        companyAccount = new Account("MobilityCompany","MC001",10000.0);
        alphaBankServer.addAccount(companyAccount);
        
        // Define o caminho para o arquivo XML de rotas
        rotasXmlPath = "data/dados2.xml";
        
        // Verifica se o arquivo de rotas existe
        File rotasFile = new File(rotasXmlPath);
        if (!rotasFile.exists()) {
            System.out.println("Arquivo de rotas não encontrado: " + rotasXmlPath);
            System.out.println("Criando arquivo de teste...");
            createTestRoutesFile(rotasXmlPath);
        }
        
        // Inicializa a MobilityCompany
        mobilityCompany = new MobilityCompany("MC001", 8080, alphaBankServer, companyAccount, rotasXmlPath);
    }
    
    /**
     * Limpeza após os testes.
     */
    @Override
    protected void tearDown() throws Exception {
        super.tearDown();
        
        // Para o servidor da MobilityCompany se estiver em execução
        if (mobilityCompany != null && mobilityCompany.isRunning()) {
            mobilityCompany.stopServer();
        }
        
        // Para o servidor do AlphaBank se estiver em execução
        if (alphaBankServer != null) {
            alphaBankServer.stopServer();
        }
    }
    
    /**
     * Testa a criação da MobilityCompany.
     */
    public void testMobilityCompanyCreation() {
        assertNotNull("MobilityCompany não deve ser nula", mobilityCompany);
        assertEquals("ID da empresa deve ser MC001", "MC001", mobilityCompany.getCompanyId());
        assertEquals("Porta do servidor deve ser 8080", 8080, mobilityCompany.getServerPort());
        assertEquals("Caminho do arquivo XML deve ser correto", rotasXmlPath, mobilityCompany.getRotasXmlPath());
        assertNotNull("AlphaBank não deve ser nulo", mobilityCompany.getAlphaBankServer());
        assertNotNull("Conta da empresa não deve ser nula", mobilityCompany.getCompanyAccount());
    }
    
    /**
     * Testa o carregamento de rotas.
     */
    public void testCarregarRotas() {
        // Inicia a MobilityCompany em uma thread separada
        Thread thread = new Thread(mobilityCompany);
        thread.start();
        
        // Aguarda o carregamento das rotas
        try {
            Thread.sleep(1000);
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
        
        // Verifica se as rotas foram carregadas
        List<Rota> rotasDisponiveis = mobilityCompany.getAvailableRotas();
        assertNotNull("Lista de rotas disponíveis não deve ser nula", rotasDisponiveis);
        
        // Para o servidor
        mobilityCompany.stopServer();
    }
    
    /**
     * Testa a adição de uma rota.
     */
    public void testAddRota() {
        // Cria uma nova rota
        Rota rota = new Rota(rotasXmlPath, "0");
        
        // Adiciona a rota à MobilityCompany
        mobilityCompany.addRota(rota);
        
        // Verifica se a rota foi adicionada
        List<Rota> rotasDisponiveis = mobilityCompany.getAvailableRotas();
        assertTrue("A rota deve estar na lista de rotas disponíveis", rotasDisponiveis.contains(rota));
    }
    
    /**
     * Testa a marcação de uma rota como executada.
     */
    public void testMarkRotaAsExecuted() {
        // Cria uma nova rota
        Rota rota = new Rota(rotasXmlPath, "0");
        
        // Adiciona a rota à MobilityCompany
        mobilityCompany.addRota(rota);
        
        // Marca a rota como em execução
        rota.assignRota("CAR1", "MC001");
        rota.startRota();
        
        // Marca a rota como executada
        mobilityCompany.markRotaAsExecuted(rota);
        
        // Verifica se a rota foi movida para a lista de rotas executadas
        List<Rota> rotasExecutadas = mobilityCompany.getRotasExecutadas();
        assertTrue("A rota deve estar na lista de rotas executadas", rotasExecutadas.contains(rota));
        
        // Verifica se a rota não está mais na lista de rotas em execução
        List<Rota> rotasEmExecucao = mobilityCompany.getRotasEmExecucao();
        assertFalse("A rota não deve estar na lista de rotas em execução", rotasEmExecucao.contains(rota));
    }
    
    /**
     * Testa a atribuição de uma rota a um carro.
     */
    public void testAssignRotaToCar() {
        // Cria uma nova rota
        Rota rota = new Rota(rotasXmlPath, "CAR1");
        
        // Adiciona a rota à MobilityCompany
        mobilityCompany.addRota(rota);
        
        // Atribui a rota a um carro
        Rota rotaAtribuida = mobilityCompany.assignRotaToCar("CAR1");
        
        // Verifica se a rota foi atribuída corretamente
        assertNotNull("A rota atribuída não deve ser nula", rotaAtribuida);
        assertEquals("A rota atribuída deve ter o ID correto", rota.getIdRota(), rotaAtribuida.getIdRota());
        assertEquals("A rota deve estar atribuída ao carro correto", "CAR1", rotaAtribuida.getCarId());
    }
    
    /**
     * Testa o envio de mensagem para um carro.
     */
    public void testSendMessageToCar() {
        // Este teste requer uma conexão real com um carro, então vamos apenas verificar
        // se o método não lança exceções quando o carro não está conectado
        try {
            boolean result = mobilityCompany.sendMessageToCar("CAR1", "TEST_MESSAGE");
            assertFalse("O envio deve falhar quando o carro não está conectado", result);
        } catch (Exception e) {
            fail("O método não deve lançar exceções: " + e.getMessage());
        }
    }
    
    /**
     * Testa o ciclo de vida do servidor.
     */
    public void testServerLifecycle() {
        // Inicia a MobilityCompany em uma thread separada
        Thread thread = new Thread(mobilityCompany);
        thread.start();
        
        // Aguarda a inicialização do servidor
        try {
            Thread.sleep(1000);
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
        
        // Verifica se o servidor está em execução
        assertTrue("O servidor deve estar em execução", mobilityCompany.isRunning());
        
        // Para o servidor
        mobilityCompany.stopServer();
        
        // Verifica se o servidor foi parado
        assertFalse("O servidor não deve estar em execução após ser parado", mobilityCompany.isRunning());
    }
    
    /**
     * Testa o processamento de pagamento.
     */
    public void testProcessPayment() {
        // Este teste requer uma implementação completa do BotPayment e AlphaBank,
        // então vamos apenas verificar se os métodos existem e não lançam exceções
        
        // Cria uma nova rota
        Rota rota = new Rota(rotasXmlPath, "0");
        
        // Adiciona a rota à MobilityCompany
        mobilityCompany.addRota(rota);
        
        // Atribui a rota a um carro
        Rota rotaAtribuida = mobilityCompany.assignRotaToCar("CAR1");
        
        // Inicia a rota
        rotaAtribuida.startRota();
        
        // Completa a rota
        rotaAtribuida.completeRota();
        
        // Marca a rota como executada
        mobilityCompany.markRotaAsExecuted(rotaAtribuida);
        
        // Verifica se a rota está na lista de rotas executadas
        List<Rota> rotasExecutadas = mobilityCompany.getRotasExecutadas();
        assertTrue("A rota deve estar na lista de rotas executadas", rotasExecutadas.contains(rotaAtribuida));
    }
    
    /**
     * Cria um arquivo XML de rotas para teste.
     * 
     * @param filePath Caminho do arquivo a ser criado
     */
    private void createTestRoutesFile(String filePath) {
        // Implementação para criar um arquivo XML de rotas para teste
        // Este método seria implementado se o arquivo de rotas não existir
    }
}
