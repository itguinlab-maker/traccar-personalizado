package org.traccar.vehicle;

public class VehicleRecord {

    private String id;
    private long deviceId;           // optional: 0 if not linked to a Traccar device
    private String plate;            // required
    private String company;          // required (empresa)
    private String internalNumber;   // número interno
    private String vehicleType;      // tipo de vehículo
    private String manufacturer;     // fabricante
    private String line;             // línea / modelo
    private Integer year;            // año
    private String engineNumber;     // número de motor
    private String chassisNumber;    // número de chasis
    private Integer passengerCapacity;
    private String color;
    private String insuranceExpiry;  // ISO date YYYY-MM-DD
    private String matricula;        // número de matrícula del vehículo
    private String wifiIp;           // IP del MDVR en red WiFi para descarga de videos

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public long getDeviceId() { return deviceId; }
    public void setDeviceId(long deviceId) { this.deviceId = deviceId; }

    public String getPlate() { return plate; }
    public void setPlate(String plate) { this.plate = plate; }

    public String getCompany() { return company; }
    public void setCompany(String company) { this.company = company; }

    public String getInternalNumber() { return internalNumber; }
    public void setInternalNumber(String internalNumber) { this.internalNumber = internalNumber; }

    public String getVehicleType() { return vehicleType; }
    public void setVehicleType(String vehicleType) { this.vehicleType = vehicleType; }

    public String getManufacturer() { return manufacturer; }
    public void setManufacturer(String manufacturer) { this.manufacturer = manufacturer; }

    public String getLine() { return line; }
    public void setLine(String line) { this.line = line; }

    public Integer getYear() { return year; }
    public void setYear(Integer year) { this.year = year; }

    public String getEngineNumber() { return engineNumber; }
    public void setEngineNumber(String engineNumber) { this.engineNumber = engineNumber; }

    public String getChassisNumber() { return chassisNumber; }
    public void setChassisNumber(String chassisNumber) { this.chassisNumber = chassisNumber; }

    public Integer getPassengerCapacity() { return passengerCapacity; }
    public void setPassengerCapacity(Integer passengerCapacity) { this.passengerCapacity = passengerCapacity; }

    public String getColor() { return color; }
    public void setColor(String color) { this.color = color; }

    public String getInsuranceExpiry() { return insuranceExpiry; }
    public void setInsuranceExpiry(String insuranceExpiry) { this.insuranceExpiry = insuranceExpiry; }

    public String getMatricula() { return matricula; }
    public void setMatricula(String matricula) { this.matricula = matricula; }

    public String getWifiIp() { return wifiIp; }
    public void setWifiIp(String wifiIp) { this.wifiIp = wifiIp; }
}
