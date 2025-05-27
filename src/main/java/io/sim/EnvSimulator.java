package io.sim;

import sim.traci4j.src.java.it.polito.appeal.traci.ReadObjectVarQuery.StringListQ;
import sim.traci4j.src.java.it.polito.appeal.traci.Repository;
import sim.traci4j.src.java.it.polito.appeal.traci.protocol.Constants;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import java.util.logging.Logger;

import de.tudresden.sumo.objects.SumoColor;
import de.tudresden.sumo.objects.SumoStringList;
import io.sim.reporting.ReportingSystem;
import io.sim.utils.GeoUtils;
import it.polito.appeal.traci.SumoTraciConnection;
import sim.traci4j.src.java.it.polito.appeal.traci.Edge;
import sim.traci4j.src.java.it.polito.appeal.traci.Lane;

/**
 * Classe responsável por gerenciar o ambiente de simulação.
 * Encapsula todos os parâmetros e lógica de simulação.
 */
public class EnvSimulator extends Thread {

    private static final Logger logger = Logger.getLogger(EnvSimulator.class.getName());
    
    // Constantes de configuração
    private static final String DEFAULT_SUMO_VEHICLE_TYPE = "DEFAULT_VEHTYPE";
    private int numDrivers = 100;
    private int numCars = 100;
    private int numRoutes = 200;
    private String routeFile = "data/dados2.xml";
    private String bankHost = "localhost";
    private int bankPort = 12345;
    private int companyPort = 12346; // Porta diferente para a MobilityCompany
    private int sumoPort = 8813;     // Porta padrão do SUMO, diferente das outras
    private double initialBalance = 1000.0;
    
    // Cores para os carros
    private final SumoColor[] CAR_COLORS = {
        new SumoColor(255, 0, 0, 126),    // Vermelho
        new SumoColor(0, 255, 0, 126),    // Verde
        new SumoColor(0, 0, 255, 126),    // Azul
        new SumoColor(255, 255, 0, 126),  // Amarelo
        new SumoColor(0, 255, 255, 126),  // Ciano
        new SumoColor(255, 0, 255, 126),  // Magenta
        new SumoColor(128, 128, 128, 126) // Cinza
    };
    
    // Tipos de combustível
    private final int[] FUEL_TYPES = {1, 2, 3, 4}; // 1-diesel, 2-gasoline, 3-ethanol, 4-hybrid
    
    // Preços de combustível
    private final double[] FUEL_PRICES = {5.20, 5.87, 4.59, 5.50};
    
    // Gerador de números aleatórios
    private final Random random = new Random();
    
    // Componentes da simulação
    private SumoTraciConnection sumo;
    private DataInputStream dis;
    private DataOutputStream dos;
    private Repository<Edge> repoEdge;
    private Repository<Lane> repoLane;
    private ReportingSystem reportingSystem;
    private AlphaBank alphaBank;
    private FuelStation fuelStation;
    private MobilityCompany mobilityCompany;
    
    // Lista de motoristas
    private List<Driver> drivers;
    
    // Controle de simulação
    private CountDownLatch simulationCompleteLatch;
    private AtomicBoolean simulationRunning = new AtomicBoolean(false);
    private Thread simulationThread;
    
    // Mapa para monitorar o estado das threads
    private ConcurrentHashMap<String, ThreadStatus> threadStatusMap = new ConcurrentHashMap<>();
    
    /**
     * Classe para monitorar o status das threads
     */
    private static class ThreadStatus {
        public String name;
        public String type;
        public long lastActive;
        public boolean isAlive;
        
        public ThreadStatus(String name, String type) {
            this.name = name;
            this.type = type;
            this.lastActive = System.currentTimeMillis();
            this.isAlive = true;
        }
        
        public void updateActivity() {
            this.lastActive = System.currentTimeMillis();
        }
    }
    
    /**
     * Construtor padrão.
     */
    public EnvSimulator() {
        // Inicializa os streams e repositórios
        this.dis = new DataInputStream(new ByteArrayInputStream(new byte[0]));
        this.dos = new DataOutputStream(new ByteArrayOutputStream());
        
        // Inicializa o controle de simulação
        this.simulationCompleteLatch = new CountDownLatch(1);
        
        // Inicializa a lista de motoristas
        this.drivers = new ArrayList<>();
        
        // Configura o logger para mostrar mais detalhes
        configureLogger();
    }
    
    /**
     * Configura o logger para mostrar mais detalhes
     */
    private void configureLogger() {
        System.setProperty("java.util.logging.SimpleFormatter.format", 
                "[%1$tF %1$tT] [%4$-7s] %5$s %n");
    }
    
    /**
     * Construtor com parâmetros personalizados.
     * 
     * @param numDrivers Número de motoristas
     * @param numCars Número de carros
     * @param numRoutes Número de rotas
     * @param routeFile Arquivo de rotas
     * @param bankHost Host do banco
     * @param bankPort Porta do banco
     * @param companyPort Porta da empresa
     * @param sumoPort Porta do SUMO
     * @param initialBalance Saldo inicial
     */
    public EnvSimulator(int numDrivers, int numCars, int numRoutes, String routeFile,
                        String bankHost, int bankPort, int companyPort, int sumoPort, double initialBalance) {
        this();
        this.numDrivers = numDrivers;
        this.numCars = numCars;
        this.numRoutes = numRoutes;
        this.routeFile = routeFile;
        this.bankHost = bankHost;
        this.bankPort = bankPort;
        this.companyPort = companyPort;
        this.sumoPort = sumoPort;
        this.initialBalance = initialBalance;
    }
    
    /**
     * Método principal da thread.
     */
    @Override
    public void run() {
        logger.info("Iniciando simulação SUMO com " + numDrivers + " Drivers, " + 
                   numCars + " Cars e " + numRoutes + " Routes");
        GeoUtils.initialize(); 
        logger.info("GeoUtils.initialize() chamado a partir do EnvSimulator.");
        try {
            // Inicializa todos os componentes
            initializeComponents();
            
            // Verifica se o SUMO está rodando
            if (sumo == null || sumo.isClosed()) {
                logger.severe("SUMO não está rodando. Abortando simulação.");
                return;
            }
            
            // Cria as rotas
            List<Rota> routes = createRoutes();
            
            // Verifica se há rotas válidas
            if (routes.isEmpty()) {
                logger.severe("Nenhuma rota válida encontrada. Abortando simulação.");
                return;
            }
            
            logger.info("Rotas válidas encontradas: " + routes.size());
            
            // Distribui as rotas para a empresa
            for (Rota rota : routes) {
                mobilityCompany.addRota(rota);
            }
            
            // Inicia o loop de simulação em uma thread separada
            // Movido para antes de iniciar os motoristas para garantir que a simulação já esteja rodando
            startSimulationLoop();
            logger.info("Loop de simulação iniciado com sucesso");
            
            // Pequena pausa para garantir que o loop de simulação esteja rodando
            Thread.sleep(2000);
            
            // Cria os carros e motoristas
            drivers = createDriversAndCars();
            
            // Distribui as rotas para os motoristas
            distributeRoutesToDrivers(drivers);
            logger.info("Distribuição de rotas concluída com sucesso");
            
            // Inicia todos os motoristas
            startDrivers(drivers);
            logger.info("Todos os motoristas iniciados com sucesso");
            
            // Inicia o monitoramento de threads
            startThreadMonitoring();
            
            // Aguarda a conclusão de todos os motoristas
            waitForDriversCompletion(drivers);
            
            // Gera relatórios finais
            generateFinalReports();
            
            logger.info("Simulação concluída com sucesso");
            
        } catch (Exception e) {
            logger.log(Level.SEVERE, "Erro durante a simulação", e);
        } finally {
            // Encerra todos os serviços
            shutdownServices();
        }
    }
    
    /**
     * Inicia o monitoramento de threads para detectar deadlocks e threads paradas
     */
    private void startThreadMonitoring() {
        Thread monitorThread = new Thread(() -> {
            logger.info("Iniciando monitoramento de threads");
            
            while (simulationRunning.get()) {
                try {
                    // Verifica o estado de todas as threads registradas
                    for (Map.Entry<String, ThreadStatus> entry : threadStatusMap.entrySet()) {
                        ThreadStatus status = entry.getValue();
                        
                        // Verifica se a thread está inativa por muito tempo (30 segundos)
                        long inactiveTime = System.currentTimeMillis() - status.lastActive;
                        if (inactiveTime > 30000 && status.isAlive) {
                            logger.warning("Thread " + status.name + " (" + status.type + ") está inativa há " + 
                                          (inactiveTime / 1000) + " segundos");
                        }
                    }
                    
                    // Verifica se há deadlocks no sistema
                    ThreadGroup rootGroup = Thread.currentThread().getThreadGroup();
                    ThreadGroup parentGroup;
                    while ((parentGroup = rootGroup.getParent()) != null) {
                        rootGroup = parentGroup;
                    }
                    
                    Thread[] threads = new Thread[rootGroup.activeCount()];
                    while (rootGroup.enumerate(threads, true) == threads.length) {
                        threads = new Thread[threads.length * 2];
                    }
                    
                    // Conta threads em BLOCKED ou WAITING state
                    int blockedCount = 0;
                    for (Thread t : threads) {
                        if (t != null) {
                            Thread.State state = t.getState();
                            if (state == Thread.State.BLOCKED || state == Thread.State.WAITING) {
                                blockedCount++;
                            }
                        }
                    }
                    
                    // Se muitas threads estão bloqueadas, pode ser um deadlock
                    if (blockedCount > 10) {
                        logger.warning("Possível deadlock detectado: " + blockedCount + " threads bloqueadas");
                    }
                    
                    Thread.sleep(5000); // Verifica a cada 5 segundos
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                } catch (Exception e) {
                    logger.log(Level.WARNING, "Erro no monitoramento de threads", e);
                }
            }
            
            logger.info("Monitoramento de threads encerrado");
        }, "Thread-Monitor");
        
        monitorThread.setDaemon(true);
        monitorThread.start();
    }
    
    /**
     * Registra uma thread para monitoramento
     * 
     * @param id Identificador da thread
     * @param type Tipo da thread (Driver, Car, TransportService, etc.)
     */
    public void registerThread(String id, String type) {
        threadStatusMap.put(id, new ThreadStatus(id, type));
    }
    
    /**
     * Atualiza o status de atividade de uma thread
     * 
     * @param id Identificador da thread
     */
    public void updateThreadActivity(String id) {
        ThreadStatus status = threadStatusMap.get(id);
        if (status != null) {
            status.updateActivity();
        }
    }
    
    /**
     * Marca uma thread como encerrada
     * 
     * @param id Identificador da thread
     */
    public void markThreadTerminated(String id) {
        ThreadStatus status = threadStatusMap.get(id);
        if (status != null) {
            status.isAlive = false;
        }
    }
    
    /**
     * Inicia o loop de simulação em uma thread separada.
     * Esta thread é responsável por avançar o tempo da simulação no SUMO.
     */
    private void startSimulationLoop() {
        logger.info("Iniciando loop de simulação SUMO");
        
        simulationRunning.set(true);
        simulationThread = new Thread(() -> {
            try {
                int step = 0;
                while (simulationRunning.get() && !sumo.isClosed()) {
                    try {
                        // Avança a simulação em um passo
                        sumo.do_timestep();
                        step++;
                        
                        // Log a cada 100 passos para não sobrecarregar o log
                        if (step % 100 == 0) {
                            logger.info("Simulação SUMO: passo " + step + " executado");
                        }
                        
                        // Pequena pausa para não sobrecarregar o sistema
                        Thread.sleep(50);
                    } catch (Exception e) {
                        logger.log(Level.WARNING, "Erro durante passo de simulação: " + e.getMessage(), e);
                        
                        // Verifica se o SUMO ainda está rodando
                        if (sumo.isClosed()) {
                            logger.severe("Conexão SUMO fechada. Encerrando loop de simulação.");
                            break;
                        }
                        
                        // Pausa maior em caso de erro para evitar spam de erros
                        Thread.sleep(1000);
                    }
                }
                logger.info("Loop de simulação SUMO encerrado após " + step + " passos");
            } catch (InterruptedException e) {
                logger.info("Loop de simulação SUMO interrompido");
                Thread.currentThread().interrupt();
            }
        }, "SUMO-Simulation-Loop");
        
        simulationThread.setDaemon(true);
        simulationThread.start();
        logger.info("Thread de loop de simulação SUMO iniciada");
        
        // Registra a thread para monitoramento
        registerThread("SUMO-Simulation-Loop", "SimulationLoop");
    }
    
    /**
     * Inicializa todos os componentes da simulação.
     * 
     * @throws Exception Se ocorrer um erro durante a inicialização
     */
    private void initializeComponents() throws Exception {
        // Inicializa o AlphaBank
        initializeAlphaBank();
        
        // Inicializa a conexão com o SUMO
        initializeSumoConnection();
        
        // Verifica se o SUMO está rodando
        if (sumo == null || sumo.isClosed()) {
            throw new Exception("Falha ao inicializar SUMO. Abortando simulação.");
        }
        
        // Inicializa os repositórios
        initializeRepositories();
        
        // Inicializa o posto de combustível
        initializeFuelStation();
        
        // Inicializa a empresa de mobilidade
        initializeMobilityCompany();

        // Inicializa o sistema de relatórios
        initializeReportingSystem();
    }
    
    /**
     * Inicializa o sistema de relatórios.
     */
    private void initializeReportingSystem() {
        logger.info("Inicializando sistema de relatórios");
        // Garanta que mobilityCompany já foi inicializada ANTES de chamar getInstance do ReportingSystem
        if (this.mobilityCompany == null) {
            logger.severe("MobilityCompany não inicializada ANTES do ReportingSystem. Abortando inicialização de relatórios.");
            // Ou lança uma exceção, ou reportingSystem permanecerá nulo.
            throw new IllegalStateException("MobilityCompany deve ser inicializada antes do ReportingSystem.");
        }
        reportingSystem = ReportingSystem.getInstance(this.mobilityCompany); // Passa a MobilityCompany
        reportingSystem.start();
    }
    
    /**
     * Inicializa a conexão com o SUMO.
     * 
     * @throws IOException Se ocorrer um erro ao iniciar o SUMO
     */
    private void initializeSumoConnection() throws IOException {
        logger.info("Inicializando conexão com o SUMO na porta " + sumoPort);
        
        String sumo_bin = "sumo-gui";
        String config_file = "map/map.sumo.cfg";
        
        try {
            sumo = new SumoTraciConnection(sumo_bin, config_file);
            
            // Adiciona opções de configuração
            sumo.addOption("start", "1");      // auto-run on GUI show
            sumo.addOption("quit-on-end", "0"); // não fechar automaticamente ao terminar
            sumo.addOption("step-length", "0.1"); // tamanho do passo de simulação
            sumo.addOption("verbose", "true"); // modo verboso para depuração
            
            // Inicia o servidor SUMO na porta especificada
            sumo.runServer(sumoPort);
            
            // Aguarda o SUMO iniciar
            Thread.sleep(5000); // Aumentado para 5 segundos para garantir inicialização completa
            
            // Verifica se o SUMO está rodando
            if (sumo.isClosed()) {
                logger.severe("SUMO não iniciou corretamente. Conexão fechada.");
                throw new IOException("SUMO não iniciou corretamente");
            }
            
            logger.info("SUMO iniciado com sucesso na porta " + sumoPort);
            
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrompido durante a inicialização do SUMO", e);
        } catch (IOException e) {
            logger.severe("Erro ao iniciar SUMO: " + e.getMessage());
            throw e;
        }
    }
    
    /**
     * Inicializa os repositórios Edge e Lane.
     */
    private void initializeRepositories() {
        logger.info("Inicializando repositórios Edge e Lane");
        
        try {
            // Inicializa os repositórios com os construtores específicos e parâmetros corretos
            repoEdge = new Repository.Edges(dis, dos,
                new StringListQ(dis, dos, Constants.CMD_GET_EDGE_VARIABLE, "edge", Constants.ID_LIST)
            );
            
            repoLane = new Repository.Lanes(dis, dos, repoEdge,
                new StringListQ(dis, dos, Constants.CMD_GET_LANE_VARIABLE, "lane", Constants.ID_LIST)
            );
            
            logger.info("Repositórios Edge e Lane inicializados com sucesso");
        } catch (Exception e) {
            logger.severe("Erro ao inicializar repositórios: " + e.getMessage());
        }
    }
    
    /**
     * Inicializa o AlphaBank.
     * 
     * @throws Exception Se ocorrer um erro ao iniciar o banco
     */
    private void initializeAlphaBank() throws Exception {
        try {
            logger.info("Inicializando AlphaBank na porta " + bankPort);
            alphaBank = new AlphaBank(bankPort);
            alphaBank.startServer();
            
            // Aguarda o banco iniciar
            Thread.sleep(2000);
            
            logger.info("AlphaBank iniciado com sucesso na porta " + bankPort);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            logger.severe("Falha ao iniciar servidor do banco: " + e.getMessage());
            throw e;
        }
    }
    
    /**
     * Inicializa o posto de combustível.
     */
    private void initializeFuelStation() {
        logger.info("Inicializando posto de combustível");
        
        // Cria uma conta para o posto
        Account fuelStationAccount = new Account("fuel_password", "fuel_station", 0.0);
        
        // Cria o posto de combustível
        fuelStation = new FuelStation("FS001", "Fuel Station", fuelStationAccount);
        fuelStation.start();
        
        // Registra a thread para monitoramento
        registerThread("FS001", "FuelStation");
        
        logger.info("Posto de combustível iniciado com sucesso");
    }
    
    /**
     * Inicializa a empresa de mobilidade.
     */
    private void initializeMobilityCompany() {
        logger.info("Inicializando empresa de mobilidade na porta " + companyPort);
        
        // Cria uma conta para a empresa
        Account companyAccount = new Account("company_password", "mobility_company", 10000.0);
        
        // Cria a empresa de mobilidade com uma porta diferente do AlphaBank
        mobilityCompany = new MobilityCompany("MC001", companyPort, alphaBank, companyAccount, routeFile);
        mobilityCompany.start();
        
        // Registra a thread para monitoramento
        registerThread("MC001", "MobilityCompany");
        
        logger.info("Empresa de mobilidade iniciada com sucesso na porta " + companyPort);
    }
    
    /**
     * Cria as rotas para a simulação.
     * 
     * @return Lista de rotas criadas
     */
    private List<Rota> createRoutes() {
        logger.info("Criando " + numRoutes + " rotas");
        
        List<Rota> routes = new ArrayList<>();
        
        try {
            for (int i = 1; i <= numRoutes; i++) {
                Rota rota = new Rota(routeFile, "Rota " + i);
                
                routes.add(rota);
            }
            
            logger.info("Rotas criadas com sucesso: " + routes.size());
        } catch (Exception e) {
            logger.log(Level.SEVERE, "Erro ao criar rotas", e);
        }
        
        return routes;
    }
    
    /**
     * Cria os motoristas e carros para a simulação.
     * 
     * @return Lista de motoristas criados
     */
    private List<Driver> createDriversAndCars() {
        logger.info("Criando " + numDrivers + " motoristas e " + numCars + " carros");
        List<Driver> driversList = new ArrayList<>();
        List<String> availableSumoRouteIDs = new ArrayList<>();

        try {
            // Obter IDs de rotas que o SUMO realmente conhece (sejam de arquivos XML ou adicionadas via TraCI)
            // Isso é crucial para o Vehicle.add funcionar.
            // Se você adiciona rotas dinamicamente ao SUMO, certifique-se que isso aconteça ANTES daqui.
            if (sumo != null && !sumo.isClosed()) {
                availableSumoRouteIDs = (List<String>) sumo.do_job_get(de.tudresden.sumo.cmd.Route.getIDList());
                if (availableSumoRouteIDs.isEmpty()) {
                    logger.severe("NENHUMA ROTA carregada ou definida no SUMO. Não é possível adicionar veículos dinamicamente sem rotas válidas.");
                    // Você pode querer lançar uma exceção aqui ou ter rotas padrão definidas em XML.
                    // Por exemplo, adicione uma rota de emergência/padrão se nenhuma for encontrada:
                    // ArrayList<String> edges = new ArrayList<>(); edges.add("edge1"); edges.add("edge2"); // Use edges válidos do seu mapa
                    // sumo.do_job_set(de.tudresden.sumo.cmd.Route.add("default_route_for_cars", edges));
                    // availableSumoRouteIDs = (List<String>) sumo.do_job_get(de.tudresden.sumo.cmd.Route.getIDList());
                    // if (availableSumoRouteIDs.isEmpty()) throw new RuntimeException("Falha ao criar/obter rota padrão no SUMO.");
                } else {
                    logger.info("Rotas disponíveis no SUMO: " + availableSumoRouteIDs);
                }
            } else {
                logger.severe("SUMO não conectado ao tentar criar carros.");
                return driversList; // Retorna lista vazia
            }

            for (int i = 1; i <= numCars; i++) { // Alterado para numCars, assumindo 1 carro por motorista
                String driverId = "D" + String.format("%03d", i);
                String carId = "CAR" + String.format("%03d", i);
                SumoColor carColor = CAR_COLORS[random.nextInt(CAR_COLORS.length)];

                // --- INÍCIO DA MODIFICAÇÃO: Adicionar veículo ao SUMO explicitamente ---
                try {
                    if (availableSumoRouteIDs.isEmpty()) {
                        logger.severe("Impossível adicionar " + carId + ": Nenhuma rota disponível no SUMO.");
                        continue; // Pula a criação deste carro/motorista
                    }
                    // Seleciona uma rota do SUMO. Pode ser necessário uma lógica mais sofisticada.
                    String initialRouteID = availableSumoRouteIDs.get( (i-1) % availableSumoRouteIDs.size() ); 
                    
                    // Verifica se o tipo de veículo existe no SUMO
                    // SumoStringList vehicleTypes = (SumoStringList) sumo.do_job_get(de.tudresden.sumo.cmd.VehicleType.getIDList());
                    // if (!vehicleTypes.contains(DEFAULT_SUMO_VEHICLE_TYPE)) {
                    //     logger.warning("Tipo de veículo padrão '" + DEFAULT_SUMO_VEHICLE_TYPE + "' não encontrado no SUMO. " +
                    //                    "O carro " + carId + " pode não ser adicionado corretamente ou usará um fallback do SUMO.");
                    //     // Considere adicionar o vType via TraCI se ele não existir:
                    //     // sumo.do_job_set(de.tudresden.sumo.cmd.VehicleType.copy("DEFAULT_VEHTYPE", "new_type_id"));
                    //     // sumo.do_job_set(de.tudresden.sumo.cmd.VehicleType.setLength("new_type_id", 5.0)); // Exemplo
                    // }

                    // Adiciona o veículo ao SUMO
                    // Parâmetros para Vehicle.add: vehID, typeID, routeID, depart (tempo em s), pos (double), speed (double), lane (byte)
                    // depart = -3 (triggered) ou -2 (containerTriggered) são comuns para adição dinâmica.
                    // Ou um tempo específico. Usar 0 para o início ou um tempo ligeiramente futuro.
                    // int departTimeSeconds = (int) (sumo.do_job_get(de.tudresden.sumo.cmd.Simulation.getCurrentTime()) / 1000.0); // Tempo atual em segundos
                    int departTime = 0; // Para depart="triggered", o tempo é simbólico, mas SUMO pode precisar que a simulação avance.
                                        // Usar depart=0 (ou um valor pequeno como 1) se "triggered" não funcionar como esperado.
                                        // O valor -3 para depart em Vehicle.add(vehID, routeID, typeID, depart,...) significa "triggered"
                                        // O método de.tudresden.sumo.cmd.Vehicle.add que você tem usa int depart, double pos, double speed, byte lane
                                        // Este parece ser um comando mais antigo/específico da biblioteca.
                                        // O padrão TraCI é (string vehID, string routeID, string typeID, string depart, string departLane, string departPos, string departSpeed, ...)

                    logger.info("Tentando adicionar " + carId + " ao SUMO com tipo " + DEFAULT_SUMO_VEHICLE_TYPE +
                                " na rota " + initialRouteID + ".");
                    
                    sumo.do_job_set(de.tudresden.sumo.cmd.Vehicle.add(
                        carId,                       // vehID
                        DEFAULT_SUMO_VEHICLE_TYPE,   // typeID
                        initialRouteID,              // routeID
                        i,                           // depart (em segundos - vamos escalonar a partida)
                        0.0,                         // departPos (posição na primeira aresta)
                        0.0,                         // departSpeed (velocidade inicial)
                        (byte) 0                     // departLane (índice 0, ou use "first")
                    ));
                    logger.info("Comando para adicionar " + carId + " ao SUMO enviado.");

                } catch (Exception eAdd) {
                    logger.log(Level.SEVERE, "Falha ao adicionar veículo " + carId + " ao SUMO: " + eAdd.getMessage(), eAdd);
                    continue; // Pula para o próximo carro se este não puder ser adicionado
                }

                // Cria o Carro
                Car car = new Car(
                    new DataInputStream(new ByteArrayInputStream(new byte[0])), // Placeholder para dis
                    new DataOutputStream(new ByteArrayOutputStream()),           // Placeholder para dos
                    repoEdge, // Precisa ser inicializado e passado
                    repoLane, // Precisa ser inicializado e passado
                    true, carId, carColor, driverId, this.sumo,
                    1000, // acquisitionRate (exemplo)
                    FUEL_TYPES[random.nextInt(FUEL_TYPES.length)], 
                    FUEL_TYPES[random.nextInt(FUEL_TYPES.length)], 
                    FUEL_PRICES[random.nextInt(FUEL_PRICES.length)],
                    4, 1 // personCapacity, personNumber
                );
                // carsList.add(car); // Se você mantiver uma lista de carros no EnvSimulator

                // Conecta o Carro à MobilityCompany
                try {
                    String companyHost = "localhost"; // Ou sua configuração
                    int companyPort = this.companyPort; 
                    logger.info("EnvSimulator: Conectando Car " + car.getIdCar() + " à MobilityCompany em " + companyHost + ":" + companyPort);
                    car.connectToCompany(companyHost, companyPort); // O Car tem este método
                    
                    // ***** INICIA A THREAD DO CARRO AQUI *****
                    if (car.isConnected()) { // Só inicia se conectou com sucesso
                        logger.info("EnvSimulator: Iniciando thread para Car " + car.getIdCar());
                        car.start(); // Chama o método start() da classe Car
                    } else {
                        logger.warning("EnvSimulator: Car " + car.getIdCar() + " não conectou à Company, thread do carro não será iniciada.");
                        // Decida o que fazer: o Driver ainda é criado? A simulação continua?
                        // Se o carro não puder enviar dados, talvez não deva participar.
                    }

                } catch (IOException e) {
                    logger.log(Level.SEVERE, "EnvSimulator: Falha crítica ao conectar Car " + car.getIdCar() + " à MobilityCompany. " + e.getMessage(), e);
                    // O carro não será iniciado se a conexão falhar aqui.
                } catch (Exception eCar) {
                    logger.log(Level.SEVERE, "EnvSimulator: Falha geral ao configurar ou conectar Car " + car.getIdCar() + ". " + eCar.getMessage(), eCar);
                }

                // Cria o Driver
                String login = "driver" + i;
                String password = "pass" + i;
                Driver driver = new Driver(
                    driverId, "Driver " + i, car,
                    bankHost, bankPort,
                    login, password,
                    initialBalance,
                    this.sumo,
                    this // Passa a instância do EnvSimulator para monitoramento
                );
                
                driversList.add(driver);
                registerThread(driverId, "Driver"); 
                // Não precisa registrar Car aqui se ele não interage com o sistema de monitoramento do EnvSimulator
                // ou se o Driver o fizer (mas Driver não tem referência ao EnvSimulator no código atual)
            }
            logger.info("Motoristas e carros criados com sucesso: " + driversList.size());
        } catch (Exception e) {
            logger.log(Level.SEVERE, "Erro ao criar motoristas e carros", e);
        }
        return driversList;
    }
    
    /**
     * Distribui as rotas para os motoristas.
     * 
     * @param drivers Lista de motoristas
     */
    private void distributeRoutesToDrivers(List<Driver> drivers) {
        logger.info("Distribuindo rotas para os motoristas");
        
        try {
            // Obtém todas as rotas da empresa
            List<Rota> allRoutes = mobilityCompany.getAvailableRotas();
            
            if (allRoutes.isEmpty()) {
                logger.warning("Nenhuma rota disponível para distribuição");
                return;
            }
            
            // Calcula quantas rotas cada motorista deve receber
            int totalDrivers = drivers.size();
            int totalRoutes = allRoutes.size();
            int baseRoutesPerDriver = totalRoutes / totalDrivers;
            int remainingRoutes = totalRoutes % totalDrivers;
            
            logger.info("Total de rotas: " + totalRoutes + ", Total de motoristas: " + totalDrivers);
            logger.info("Rotas base por motorista: " + baseRoutesPerDriver + ", Rotas restantes: " + remainingRoutes);
            
            // Distribui as rotas de forma determinística
            int routeIndex = 0;
            for (int i = 0; i < totalDrivers; i++) {
                Driver driver = drivers.get(i);
                
                // Cada motorista recebe pelo menos baseRoutesPerDriver rotas
                int routesForThisDriver = baseRoutesPerDriver;
                
                // Alguns motoristas recebem uma rota adicional para distribuir as restantes
                if (i < remainingRoutes) {
                    routesForThisDriver++;
                }
                
                // Adiciona as rotas ao motorista
                for (int j = 0; j < routesForThisDriver && routeIndex < totalRoutes; j++) {
                    Rota rota = allRoutes.get(routeIndex++);
                    driver.addRota(rota);
                    logger.info("Rota " + rota.getIdRota() + " adicionada ao Driver " + driver.getDriverId());
                }
            }
            
            // Verifica se todas as rotas foram distribuídas
            if (routeIndex < totalRoutes) {
                logger.warning("Nem todas as rotas foram distribuídas: " + routeIndex + "/" + totalRoutes);
            } else {
                logger.info("Todas as rotas foram distribuídas com sucesso: " + routeIndex + "/" + totalRoutes);
            }
        } catch (Exception e) {
            logger.log(Level.SEVERE, "Erro ao distribuir rotas", e);
        }
    }
    
    /**
     * Inicia todos os motoristas.
     * 
     * @param drivers Lista de motoristas
     */
    private void startDrivers(List<Driver> drivers) {
        logger.info("Iniciando " + drivers.size() + " motoristas");
        
        try {
            int count = 0;
            for (Driver driver : drivers) {
                // Inicia o motorista
                driver.start();
                count++;
                
                // Log a cada 10 motoristas para não sobrecarregar o log
                if (count % 10 == 0) {
                    logger.info(count + " motoristas iniciados");
                }
                
                // Pequena pausa para evitar sobrecarga
                Thread.sleep(100);
            }
            
            logger.info("Todos os motoristas iniciados com sucesso: " + count);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            logger.log(Level.SEVERE, "Interrompido durante a inicialização dos motoristas", e);
        } catch (Exception e) {
            logger.log(Level.SEVERE, "Erro ao iniciar motoristas", e);
        }
    }
    
    /**
     * Aguarda a conclusão de todos os motoristas.
     * 
     * @param drivers Lista de motoristas
     */
    private void waitForDriversCompletion(List<Driver> drivers) {
        logger.info("Aguardando a conclusão de " + drivers.size() + " motoristas");
        
        try {
            for (Driver driver : drivers) {
                try {
                    // Define um timeout para evitar espera infinita
                    driver.join(60000); // 60 segundos de timeout
                    
                    // Verifica se o motorista ainda está vivo após o timeout
                    if (driver.isAlive()) {
                        logger.warning("Timeout ao aguardar o Driver " + driver.getDriverId() + ". Continuando...");
                    } else {
                        logger.info("Driver " + driver.getDriverId() + " concluído");
                        
                        // Marca a thread como encerrada no monitoramento
                        markThreadTerminated(driver.getDriverId());
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    logger.warning("Interrompido ao aguardar o Driver " + driver.getDriverId());
                }
            }
            
            logger.info("Todos os motoristas concluídos ou com timeout");
        } catch (Exception e) {
            logger.log(Level.SEVERE, "Erro ao aguardar motoristas", e);
        }
    }
    
    /**
     * Gera relatórios finais da simulação.
     */
    private void generateFinalReports() {
        logger.info("Gerando relatórios finais");
        
        try {
            // Aqui seria implementada a lógica para gerar relatórios finais
            // Por exemplo, relatórios de consumo de combustível, distância percorrida, etc.
            
            // Exemplo: gerar relatório de consumo de combustível
            if (reportingSystem != null) {
                reportingSystem.generateFinalReports();
                logger.info("Relatórios finais gerados com sucesso");
            } else {
                logger.warning("Sistema de relatórios não inicializado");
            }
        } catch (Exception e) {
            logger.log(Level.SEVERE, "Erro ao gerar relatórios finais", e);
        }
    }
    
    /**
     * Encerra todos os serviços.
     */
    private void shutdownServices() {
        logger.info("Encerrando serviços");
        
        try {
            // Encerra o loop de simulação
            simulationRunning.set(false);
            if (simulationThread != null && simulationThread.isAlive()) {
                try {
                    simulationThread.join(5000); // 5 segundos de timeout
                    if (simulationThread.isAlive()) {
                        logger.warning("Timeout ao encerrar o loop de simulação. Interrompendo...");
                        simulationThread.interrupt();
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            
            // Encerra o SUMO
            if (sumo != null && !sumo.isClosed()) {
                try {
                    sumo.close();
                    logger.info("SUMO encerrado com sucesso");
                } catch (Exception e) {
                    logger.log(Level.WARNING, "Erro ao encerrar SUMO", e);
                }
            }
            
            // Encerra o AlphaBank
            if (alphaBank != null) {
                try {
                    alphaBank.stopServer();
                    logger.info("AlphaBank encerrado com sucesso");
                } catch (Exception e) {
                    logger.log(Level.WARNING, "Erro ao encerrar AlphaBank", e);
                }
            }
            
            // Encerra a MobilityCompany
            if (mobilityCompany != null) {
                try {
                    mobilityCompany.stopServer();
                    logger.info("MobilityCompany encerrada com sucesso");
                } catch (Exception e) {
                    logger.log(Level.WARNING, "Erro ao encerrar MobilityCompany", e);
                }
            }
            
            // Encerra o sistema de relatórios
            if (reportingSystem != null) {
                try {
                    reportingSystem.stop();
                    logger.info("Sistema de relatórios encerrado com sucesso");
                } catch (Exception e) {
                    logger.log(Level.WARNING, "Erro ao encerrar sistema de relatórios", e);
                }
            }
            
            // Libera o latch para indicar que a simulação foi concluída
            simulationCompleteLatch.countDown();
            
            logger.info("Todos os serviços encerrados com sucesso");
        } catch (Exception e) {
            logger.log(Level.SEVERE, "Erro ao encerrar serviços", e);
        }
    }
    
    /**
     * Aguarda a conclusão da simulação.
     * 
     * @param timeout Tempo máximo de espera em milissegundos
     * @return true se a simulação foi concluída, false se ocorreu timeout
     * @throws InterruptedException Se a thread for interrompida durante a espera
     */
    public boolean waitForCompletion(long timeout) throws InterruptedException {
        return simulationCompleteLatch.await(timeout, TimeUnit.MILLISECONDS);
    }
    
    /**
     * Obtém a lista de motoristas.
     * 
     * @return Lista de motoristas
     */
    public List<Driver> getDrivers() {
        return drivers;
    }
    
    /**
     * Obtém a conexão com o SUMO.
     * 
     * @return Conexão com o SUMO
     */
    public SumoTraciConnection getSumo() {
        return sumo;
    }
    
    /**
     * Obtém o AlphaBank.
     * 
     * @return AlphaBank
     */
    public AlphaBank getAlphaBank() {
        return alphaBank;
    }
    
    /**
     * Obtém o posto de combustível.
     * 
     * @return Posto de combustível
     */
    public FuelStation getFuelStation() {
        return fuelStation;
    }
    
    /**
     * Obtém a empresa de mobilidade.
     * 
     * @return Empresa de mobilidade
     */
    public MobilityCompany getMobilityCompany() {
        return mobilityCompany;
    }
    
    /**
     * Obtém o sistema de relatórios.
     * 
     * @return Sistema de relatórios
     */
    public ReportingSystem getReportingSystem() {
        return reportingSystem;
    }
}
