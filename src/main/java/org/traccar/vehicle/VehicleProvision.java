package org.traccar.vehicle;

import java.util.HashMap;
import java.util.Map;

/**
 * Payload del alta unificada de vehículos: datos del vehículo + especificación de
 * los dispositivos/cámaras a crear, vincular o actualizar en una sola operación.
 */
public class VehicleProvision {

    private VehicleRecord vehicle;
    private String cameraType;   // "streamax" (un equipo centralizado) | "hikvision" (una cámara por puerta)
    private DeviceSpec front;    // dispositivo/cámara delantera (o el MDVR en streamax)
    private DeviceSpec rear;     // dispositivo/cámara trasera (solo hikvision)

    public VehicleRecord getVehicle() {
        return vehicle;
    }

    public void setVehicle(VehicleRecord vehicle) {
        this.vehicle = vehicle;
    }

    public String getCameraType() {
        return cameraType;
    }

    public void setCameraType(String cameraType) {
        this.cameraType = cameraType;
    }

    public DeviceSpec getFront() {
        return front;
    }

    public void setFront(DeviceSpec front) {
        this.front = front;
    }

    public DeviceSpec getRear() {
        return rear;
    }

    public void setRear(DeviceSpec rear) {
        this.rear = rear;
    }

    /**
     * Especificación de un dispositivo: crear uno nuevo, vincular uno existente,
     * o vincular y actualizar sus atributos.
     */
    public static class DeviceSpec {

        private String mode;                 // "create" | "link" | "update"
        private long deviceId;               // para link/update
        private String uniqueId;             // para create
        private String name;                 // para create
        private Map<String, Object> attributes = new HashMap<>();

        public String getMode() {
            return mode;
        }

        public void setMode(String mode) {
            this.mode = mode;
        }

        public long getDeviceId() {
            return deviceId;
        }

        public void setDeviceId(long deviceId) {
            this.deviceId = deviceId;
        }

        public String getUniqueId() {
            return uniqueId;
        }

        public void setUniqueId(String uniqueId) {
            this.uniqueId = uniqueId;
        }

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        public Map<String, Object> getAttributes() {
            return attributes;
        }

        public void setAttributes(Map<String, Object> attributes) {
            this.attributes = attributes != null ? attributes : new HashMap<>();
        }
    }
}
