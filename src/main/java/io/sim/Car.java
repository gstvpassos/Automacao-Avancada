package io.sim;

import io.sim.reporting.ReportingSystem;

import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.net.Socket;
import java.util.ArrayList;
import java.util.logging.Logger;

import de.tudresden.sumo.cmd.Vehicle;
import sim.traci4j.src.java.it.polito.appeal.traci.Repository;
import sim.traci4j.src.java.it.polito.appeal.traci.Edge;
import sim.traci4j.src.java.it.polito.appeal.traci.Lane;
import de.tudresden.sumo.objects.SumoColor;
import de.tudresden.sumo.objects.SumoPosition2D;
import it.polito.appeal.traci.SumoTraciConnection;
import io.sim.utils.JsonUtil;
import io.sim.utils.GeoUtils;

/**
 * Classe que representa um carro no sistema de simulação.
 * Implementa Runnable para execução concorrente e atua como cliente para o servidor Company.
 */
public class Car extends sim.traci4j.src.java.it.polito.appeal.traci.Vehicle implements Runnable {

    private static final Logger logger = Logger.getLogger(Car.class.getName());
    private static final double REFUEL_THRESHOLD = 3.0; // Limite para abastecimento (3 litros)
    private static final double INITIAL_FUEL = 10.0; // Combustível inicial (10 litros)
    private static final long REFUEL_TIME = 120000; // Tempo de abastecimento (2 minutos em milissegundos)

    // Identificação do carro
    private String idCar;
    private SumoColor colorCar;
    private String driverID;
    private SumoTraciConnection sumo;

    // Controle de execução
    private boolean on_off;
    private long acquisitionRate;
    
    // Características do veículo
    private int fuelType;            // 1-diesel, 2-gasoline, 3-ethanol, 4-hybrid
    private int fuelPreferential;    // 1-diesel, 2-gasoline, 3-ethanol, 4-hybrid
    private double fuelPrice;        // price in liters
    private int personCapacity;      // the total number of persons that can ride in this vehicle
    private int personNumber;        // the total number of persons which are riding in this vehicle

    // Conexão com servidores
    private MobilityCompany companyServer; // Para enviar dados
    private FuelStation fuelStation;       // Para solicitar abastecimento
    private Socket companyConnection;
    private ObjectOutputStream companyOut;
    private ObjectInputStream companyIn;
    private boolean connected;
    
    // Sistema de relatórios
    private ReportingSystem reportingSystem;
    
    // Tanque de combustível
    private double fuelTank;
    private boolean refueling;
    private long refuelingStartTime;
    
    // Dados de condução e relatórios
    private ArrayList<DrivingData> drivingReport;
    private SumoPosition2D lastPosition;
    private double totalDistance;
    private double distanceSinceLastReport;
    
    /**
     * Construtor principal do Car.
     * 
     * @param _on_off Estado inicial (ligado/desligado)
     * @param _idCar ID do carro
     * @param _colorCar Cor do carro
     * @param _driverID ID do motorista
     * @param _sumo Conexão com o SUMO
     * @param _acquisitionRate Taxa de aquisição de dados
     * @param _fuelType Tipo de combustível
     * @param _fuelPreferential Tipo de combustível preferencial
     * @param _fuelPrice Preço do combustível
     * @param _personCapacity Capacidade de pessoas
     * @param _personNumber Número de pessoas
     * @throws Exception Se ocorrer um erro na inicialização
     */
    public Car(DataInputStream _dis, DataOutputStream _dos, Repository<Edge> _repoEdge, Repository<Lane> _repoLane, boolean _on_off, String _idCar, SumoColor _colorCar, String _driverID, SumoTraciConnection _sumo, 
            long _acquisitionRate, int _fuelType, int _fuelPreferential, double _fuelPrice, 
            int _personCapacity, int _personNumber) throws Exception {
        
        // Chama o construtor da classe pai (Vehicle)
        super(_dis, _dos, _idCar, _repoEdge, _repoLane);
        
        this.on_off = _on_off;
        this.idCar = _idCar;
        this.colorCar = _colorCar;
        this.driverID = _driverID;
        this.sumo = _sumo;
        this.acquisitionRate = _acquisitionRate;
        
        if((_fuelType < 0) || (_fuelType > 4)) {
            this.fuelType = 4;
        } else {
            this.fuelType = _fuelType;
        }
        
        if((_fuelPreferential < 0) || (_fuelPreferential > 4)) {
            this.fuelPreferential = 4;
        } else {
            this.fuelPreferential = _fuelPreferential;
        }

        this.fuelPrice = _fuelPrice;
        this.personCapacity = _personCapacity;
        this.personNumber = _personNumber;
        this.drivingReport = new ArrayList<>();
        
        // Inicializa o tanque de combustível com 10 litros
        this.fuelTank = INITIAL_FUEL;
        this.refueling = false;
        
        // Inicializa contadores de distância
        this.totalDistance = 0.0;
        this.distanceSinceLastReport = 0.0;
        
        logger.info("Car " + idCar + " criado com " + INITIAL_FUEL + "L de combustível.");
    }

    /**
     * Conecta ao servidor da Company.
     * 
     * @param host Host do servidor
     * @param port Porta do servidor
     * @throws IOException Se ocorrer um erro de conexão
     */
    public void connectToCompany(String host, int port) throws IOException {
        try {
            this.companyConnection = new Socket(host, port);
            this.companyOut = new ObjectOutputStream(companyConnection.getOutputStream());
            this.companyIn = new ObjectInputStream(companyConnection.getInputStream());
            
            // Envia mensagem de registro
            sendRegistrationToCompany();
            
            this.connected = true;
            logger.info("Car " + idCar + " conectado ao servidor Company");
        } catch (IOException e) {
            logger.severe("Erro ao conectar ao servidor Company: " + e.getMessage());
            throw e;
        }
    }
    
    /**
     * Envia mensagem de registro para o servidor Company.
     * 
     * @throws IOException Se ocorrer um erro de comunicação
     */
    private void sendRegistrationToCompany() throws IOException {
        try {
            // Cria objeto de registro
            String registrationMessage = JsonUtil.toJson(new CarRegistration(
                    this.idCar, 
                    this.driverID, 
                    "CAR"));
            
            // Envia para o servidor
            companyOut.writeObject(registrationMessage);
            companyOut.flush();
            
            // Aguarda resposta
            String response = (String) companyIn.readObject();
            
            // Verifica se o registro foi bem-sucedido
            if (!response.contains("\"status\":\"success\"")) {
                throw new IOException("Falha no registro: " + response);
            }
        } catch (Exception e) {
            logger.severe("Erro no registro com a Company: " + e.getMessage());
            throw new IOException("Erro no registro", e);
        }
    }
    
    /**
     * Define a Fuel Station para este carro.
     * 
     * @param fuelStation Instância da Fuel Station
     */
    public void setFuelStation(FuelStation fuelStation) {
        this.fuelStation = fuelStation;
    }
    
	/**
     * Define o sistema de relatórios para este carro.
     * 
     * @param reportingSystem Sistema de relatórios a ser utilizado
     */
    public void setReportingSystem(ReportingSystem reportingSystem) {
        this.reportingSystem = reportingSystem;
    }
	
    /**
     * Define o servidor Company para este carro.
     * 
     * @param companyServer Instância do servidor Company
     */
    public void setCompanyServer(MobilityCompany companyServer) {
        this.companyServer = companyServer;
    }

    /**
     * Método principal da thread.
     * Gerencia a execução do carro, atualizando sensores e enviando dados.
     */
    @Override
    public void run() {
        while (this.on_off) {
            try {
                // Verifica se está em processo de abastecimento
                if (refueling) {
                    handleRefueling();
                } else {
                    // Atualiza sensores e envia dados
                    Thread.sleep(this.acquisitionRate);
                    this.atualizaSensores();
                    
                    // Envia dados para a Company se estiver conectado
                    if (connected && companyConnection != null && !companyConnection.isClosed()) {
                        sendDrivingDataToCompany();
                    }
                }
            } catch (InterruptedException e) {
                logger.warning("Car " + idCar + " thread interrompida: " + e.getMessage());
                break;
            } catch (Exception e) {
                logger.severe("Erro na execução do Car " + idCar + ": " + e.getMessage());
                e.printStackTrace();
            }
        }
        
        // Limpa recursos ao encerrar
        cleanup();
    }

    /**
     * Inicia a execução do carro como uma thread.
     */
    public void start() {
        Thread t = new Thread(this);
        t.start();
    }

    /**
     * Atualiza os sensores do carro e coleta dados de condução.
     */
    public void atualizaSensores() {
        try {
            if (!this.getSumo().isClosed()) {
                // Obtém a posição atual
                SumoPosition2D currentPosition = null;
                try {
                    double[] coords = (double[]) sumo.do_job_get(Vehicle.getPosition(this.idCar));
                    currentPosition = new SumoPosition2D(coords[0], coords[1]);
                } catch (Exception e) {
                    logger.warning("Erro ao obter posição do carro " + idCar + ": " + e.getMessage());
                    return;
                }
                
                // Calcula a distância percorrida desde a última atualização
                double distanceDelta = 0.0;
                if (lastPosition != null) {
                    distanceDelta = calculateDistance(lastPosition, currentPosition);
                    totalDistance += distanceDelta;
                    distanceSinceLastReport += distanceDelta;
                }
                lastPosition = currentPosition;
                
                // Obtém dados do SUMO
                String roadID = "unknown";
                String routeID = "unknown";
                Integer routeIndex = -1;
                double speed = 0.0;
                double odometer = 0.0;
                double fuelConsumption = 0.0;
                double co2Emission = 0.0;
                double hcEmission = 0.0;
                
                try {
                    Object roadIDObj = this.sumo.do_job_get(Vehicle.getRoadID(this.idCar));
                    roadID = (roadIDObj != null) ? (String) roadIDObj : "unknown";
                    
                    Object routeIDObj = this.sumo.do_job_get(Vehicle.getRouteID(this.idCar));
                    routeID = (routeIDObj != null) ? (String) routeIDObj : "unknown";
                    
                    Object routeIndexObj = this.sumo.do_job_get(Vehicle.getRouteIndex(this.idCar));
                    routeIndex = (routeIndexObj != null) ? (Integer) routeIndexObj : -1;
                    
                    Object speedObj = sumo.do_job_get(Vehicle.getSpeed(this.idCar));
                    speed = (speedObj != null) ? (double) speedObj : 0.0;
                    
                    Object odometerObj = sumo.do_job_get(Vehicle.getDistance(this.idCar));
                    odometer = (odometerObj != null) ? (double) odometerObj : 0.0;
                    
                    Object fuelConsumptionObj = sumo.do_job_get(Vehicle.getFuelConsumption(this.idCar));
                    fuelConsumption = (fuelConsumptionObj != null) ? (double) fuelConsumptionObj : 0.0;
                    
                    Object co2EmissionObj = sumo.do_job_get(Vehicle.getCO2Emission(this.idCar));
                    co2Emission = (co2EmissionObj != null) ? (double) co2EmissionObj : 0.0;
                    
                    Object hcEmissionObj = sumo.do_job_get(Vehicle.getHCEmission(this.idCar));
                    hcEmission = (hcEmissionObj != null) ? (double) hcEmissionObj : 0.0;
                } catch (Exception e) {
                    logger.warning("Erro ao obter dados do SUMO para o carro " + idCar + ": " + e.getMessage());
                }
                
                System.out.println("AutoID: " + this.getIdCar());
                System.out.println("RoadID: " + roadID);
                System.out.println("RouteID: " + routeID);
                System.out.println("RouteIndex: " + routeIndex);
                
                // Converte coordenadas para geográficas
                double[] geoCoords = GeoUtils.convertToGeo(currentPosition.x, currentPosition.y);
                double lon = geoCoords[0];
                double lat = geoCoords[1];
                
                // Atualiza o consumo de combustível
                // Converte de mg/s para litros considerando o tempo desde a última atualização
                double fuelConsumedLiters = convertFuelConsumptionToLiters(fuelConsumption, this.acquisitionRate);
                updateFuelTank(fuelConsumedLiters);
                
                // Cria relatório de condução
                DrivingData report = new DrivingData(
                        this.idCar, 
                        this.driverID, 
                        System.currentTimeMillis(), 
                        currentPosition.x, 
                        currentPosition.y,
						geoCoords,
                        roadID,
                        routeID,
                        speed,
                        odometer,
                        fuelConsumption,
                        calculateAverageFuelConsumption(),
                        this.fuelType, 
                        this.fuelPrice,
                        co2Emission,
                        hcEmission,
                        this.personCapacity,
                        this.personNumber
                );
                
                // Adiciona coordenadas geográficas ao relatório
                // Nota: Como DrivingData não tem setters para lat/lon, seria necessário modificar a classe
                // ou criar uma classe estendida. Por enquanto, apenas armazenamos os valores.
                
                // Adiciona o relatório à lista
                this.drivingReport.add(report);
                
                // Exibe informações no console
                System.out.println("idCar = " + this.idCar);
                System.out.println("speed = " + speed);
                System.out.println("odometer = " + odometer);
                System.out.println("Fuel Consumption = " + fuelConsumption);
                System.out.println("Fuel Tank = " + this.fuelTank + " litros");
                System.out.println("CO2 Emission = " + co2Emission);
                System.out.println("Longitude = " + lon + ", Latitude = " + lat);
                System.out.println("getPersonNumber = " + this.personNumber);
                System.out.println("************************");
                
                // Verifica se precisa abastecer
                if (this.fuelTank <= REFUEL_THRESHOLD && !refueling) {
                    startRefueling();
                }
                
                // Controla a velocidade do veículo
                try {
                    // Se não estiver abastecendo, mantém a velocidade normal
                    if (!refueling) {
                        sumo.do_job_set(Vehicle.setSpeedMode(this.idCar, 0));
                        sumo.do_job_set(Vehicle.setSpeed(this.idCar, 10));
                    }
                } catch (Exception e) {
                    logger.warning("Erro ao definir velocidade do carro " + idCar + ": " + e.getMessage());
                }

            } else {
                System.out.println("SUMO is closed...");
            }
        } catch (Exception e) {
            logger.severe("Erro ao atualizar sensores do carro " + idCar + ": " + e.getMessage());
            e.printStackTrace();
        }
    }
    
    /**
     * Inicia o processo de abastecimento.
     */
    private void startRefueling() {
        try {
            if (fuelStation == null) {
                logger.warning("Car " + idCar + " precisa abastecer, mas não há Fuel Station configurada");
                return;
            }
            
            logger.info("Car " + idCar + " iniciando abastecimento. Combustível atual: " + fuelTank + " litros");
            
            // Para o veículo
            sumo.do_job_set(Vehicle.setSpeed(this.idCar, 0));
            
            // Marca como abastecendo
            this.refueling = true;
            this.refuelingStartTime = System.currentTimeMillis();
            
            // Solicita abastecimento à Fuel Station
            // Nota: A implementação completa dependeria da interface da FuelStation
            // Por enquanto, apenas simulamos o abastecimento
            double amountToRefuel = INITIAL_FUEL - fuelTank;
            logger.info("Car " + idCar + " solicitando abastecimento de " + amountToRefuel + " litros");
            
            // Em uma implementação real, chamaria um método da FuelStation
            // fuelStation.requestRefuel(this, amountToRefuel);
        } catch (Exception e) {
            logger.severe("Erro ao iniciar abastecimento do carro " + idCar + ": " + e.getMessage());
            this.refueling = false;
        }
    }
    
    /**
     * Gerencia o processo de abastecimento em andamento.
     */
    private void handleRefueling() throws InterruptedException {
        // Verifica se o tempo de abastecimento já passou (2 minutos)
        long currentTime = System.currentTimeMillis();
        long elapsedTime = currentTime - refuelingStartTime;
        
        if (elapsedTime >= REFUEL_TIME) {
            // Abastecimento concluído
            completeRefueling();
        } else {
            // Ainda abastecendo, mantém o veículo parado
            try {
                sumo.do_job_set(Vehicle.setSpeed(this.idCar, 0));
            } catch (Exception e) {
                logger.warning("Erro ao manter veículo parado durante abastecimento: " + e.getMessage());
            }
            
            // Aguarda um pouco antes de verificar novamente
            Thread.sleep(1000);
        }
    }
    
    /**
     * Finaliza o processo de abastecimento.
     */
    private void completeRefueling() {
        try {
            // Simula o abastecimento completo
            this.fuelTank = INITIAL_FUEL;
            this.refueling = false;
            
            logger.info("Car " + idCar + " concluiu o abastecimento. Combustível atual: " + fuelTank + " litros");
            
            // Em uma implementação real, processaria o pagamento aqui
            // Isso seria feito pelo Driver através do BotPayment
        } catch (Exception e) {
            logger.severe("Erro ao completar abastecimento do carro " + idCar + ": " + e.getMessage());
        }
    }
    
    /**
     * Atualiza o nível do tanque de combustível com base no consumo.
     * 
     * @param consumedLiters Quantidade de litros consumidos
     */
    private void updateFuelTank(double consumedLiters) {
        // Atualiza o nível do tanque
        this.fuelTank -= consumedLiters;
        
        // Garante que não fique negativo
        if (this.fuelTank < 0) {
            this.fuelTank = 0;
        }
    }
    
    /**
     * Converte o consumo de combustível de mg/s para litros.
     * 
     * @param fuelConsumptionMgPerS Consumo em mg/s
     * @param timeMs Tempo em milissegundos
     * @return Consumo em litros
     */
    private double convertFuelConsumptionToLiters(double fuelConsumptionMgPerS, long timeMs) {
        // Converte mg/s para g/s
        double fuelConsumptionGPerS = fuelConsumptionMgPerS / 1000.0;
        
        // Converte g/s para g no período
        double fuelConsumptionG = fuelConsumptionGPerS * (timeMs / 1000.0);
        
        // Converte g para litros (densidade aproximada da gasolina: 750g/L)
        double densityGPerL = 750.0;
        return fuelConsumptionG / densityGPerL;
    }
    
    /**
     * Calcula o consumo médio de combustível.
     * 
     * @return Consumo médio em km/L
     */
    private double calculateAverageFuelConsumption() {
        if (drivingReport.isEmpty()) {
            return 0.0;
        }
        
        // Calcula a média dos últimos relatórios
        double totalConsumption = 0.0;
        int count = 0;
        int maxSamples = Math.min(10, drivingReport.size());
        
        for (int i = drivingReport.size() - 1; i >= drivingReport.size() - maxSamples; i--) {
            totalConsumption += drivingReport.get(i).getFuelConsumption();
            count++;
        }
        
        return (count > 0) ? totalConsumption / count : 0.0;
    }
    
    /**
     * Calcula a distância entre duas posições.
     * 
     * @param pos1 Primeira posição
     * @param pos2 Segunda posição
     * @return Distância em metros
     */
    public double calculateDistance(SumoPosition2D pos1, SumoPosition2D pos2) {
        // Cálculo da distância euclidiana
        double dx = pos2.x - pos1.x;
        double dy = pos2.y - pos1.y;
        return Math.sqrt(dx * dx + dy * dy);
    }
    
    /**
     * Envia dados de condução para o servidor Company.
     */
    private void sendDrivingDataToCompany() {
        try {
            // Verifica se há dados para enviar
            if (drivingReport.isEmpty()) {
                return;
            }
            
            // Obtém o último relatório
            DrivingData lastReport = drivingReport.get(drivingReport.size() - 1);
            
            // Converte para JSON
            String reportJson = JsonUtil.toJson(lastReport);
            
            // Envia para o servidor
            companyOut.writeObject(reportJson);
            companyOut.flush();
            
            logger.fine("Car " + idCar + " enviou dados para Company");
        } catch (Exception e) {
            logger.warning("Erro ao enviar dados para Company: " + e.getMessage());
            
            // Tenta reconectar em caso de erro
            try {
                reconnectToCompany();
            } catch (Exception reconnectError) {
                logger.severe("Falha ao reconectar à Company: " + reconnectError.getMessage());
            }
        }
    }
    
    /**
     * Tenta reconectar ao servidor Company.
     * 
     * @throws Exception Se ocorrer um erro na reconexão
     */
    private void reconnectToCompany() throws Exception {
        logger.info("Car " + idCar + " tentando reconectar ao servidor Company...");
        
        // Fecha conexão atual se existir
        if (companyConnection != null && !companyConnection.isClosed()) {
            try {
                companyConnection.close();
            } catch (IOException e) {
                // Ignora erros ao fechar
            }
        }
        
        this.connected = false;
        
        // Tenta reconectar
        String host = companyConnection.getInetAddress().getHostName();
        int port = companyConnection.getPort();
        
        // Reconecta
        connectToCompany(host, port);
    }
    
    /**
     * Limpa recursos ao encerrar o carro.
     */
    private void cleanup() {
        // Fecha conexão com a Company
        try {
            if (companyOut != null) companyOut.close();
            if (companyIn != null) companyIn.close();
            if (companyConnection != null && !companyConnection.isClosed()) {
                companyConnection.close();
            }
        } catch (IOException e) {
            logger.warning("Erro ao fechar conexões: " + e.getMessage());
        }
        
        logger.info("Car " + idCar + " finalizado");
    }
    
    /**
     * Classe interna para representar uma solicitação de registro.
     */
    private static class CarRegistration {
        private String carId;
        private String driverId;
        private String clientType;
        
        public CarRegistration(String carId, String driverId, String clientType) {
            this.carId = carId;
            this.driverId = driverId;
            this.clientType = clientType;
        }
        
        // Getters necessários para serialização JSON
        public String getCarId() {
            return carId;
        }
        
        public String getDriverId() {
            return driverId;
        }
        
        public String getClientType() {
            return clientType;
        }
    }
    
    // Getters e Setters
    
    public boolean isOn_off() {
        return this.on_off;
    }

    public void setOn_off(boolean _on_off) {
        this.on_off = _on_off;
    }

    public long getAcquisitionRate() {
        return this.acquisitionRate;
    }

    public void setAcquisitionRate(long _acquisitionRate) {
        this.acquisitionRate = _acquisitionRate;
    }

    public String getIdCar() {
        return this.idCar;
    }

    public void setIdCar(String idCar) {
        this.idCar = idCar;
    }

    public SumoColor getColorCar() {
        return colorCar;
    }

    public void setColorCar(SumoColor colorCar) {
        this.colorCar = colorCar;
    }

    public String getDriverID() {
        return driverID;
    }

    public void setDriverID(String driverID) {
        this.driverID = driverID;
    }

    public SumoTraciConnection getSumo() {
        return this.sumo;
    }

    public void setSumo(SumoTraciConnection sumo) {
        this.sumo = sumo;
    }

    public int getFuelType() {
        return this.fuelType;
    }

    public void setFuelType(int _fuelType) {
        if((_fuelType < 0) || (_fuelType > 4)) {
            this.fuelType = 4;
        } else {
            this.fuelType = _fuelType;
        }
    }

    public double getFuelPrice() {
        return this.fuelPrice;
    }

    public void setFuelPrice(double _fuelPrice) {
        this.fuelPrice = _fuelPrice;
    }

    public SumoColor getcolorCar() {
        return this.colorCar;
    }

    public int getFuelPreferential() {
        return this.fuelPreferential;
    }

    public void setFuelPreferential(int _fuelPreferential) {
        if((_fuelPreferential < 0) || (_fuelPreferential > 4)) {
            this.fuelPreferential = 4;
        } else {
            this.fuelPreferential = _fuelPreferential;
        }
    }

    public int getPersonCapacity() {
        return this.personCapacity;
    }

    public int getPersonNumber() {
        return this.personNumber;
    }

    public void setPersonCapacity(int personCapacity) {
        this.personCapacity = personCapacity;
    }

    public void setPersonNumber(int personNumber) {
        this.personNumber = personNumber;
    }

    public ArrayList<DrivingData> getDrivingRepport() {
        return drivingReport;
    }

    public void setDrivingRepport(ArrayList<DrivingData> drivingRepport) {
        this.drivingReport = drivingRepport;
    }
    
    public double getFuelTank() {
        return fuelTank;
    }
    
	public void setFuelTank(double fuelTank){
		this.fuelTank = fuelTank;
	}

    public boolean isRefueling() {
        return refueling;
    }
    
	public void setRefueling(boolean refueling){
		this.refueling = refueling;
	}

    public double getTotalDistance() {
        return totalDistance;
    }
    
    public boolean isConnected() {
        return connected;
    }
}
