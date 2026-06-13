package org.traccar.forwarding;

import java.time.Instant;

public class DispatchJob {

    private String id;
    private ApiGroup group;
    private String payloadJson;
    private long deviceId;
    private String placa;
    private int attempts;
    private Instant nextAttemptAt;

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public ApiGroup getGroup() { return group; }
    public void setGroup(ApiGroup group) { this.group = group; }

    public String getPayloadJson() { return payloadJson; }
    public void setPayloadJson(String payloadJson) { this.payloadJson = payloadJson; }

    public long getDeviceId() { return deviceId; }
    public void setDeviceId(long deviceId) { this.deviceId = deviceId; }

    public String getPlaca() { return placa; }
    public void setPlaca(String placa) { this.placa = placa; }

    public int getAttempts() { return attempts; }
    public void setAttempts(int attempts) { this.attempts = attempts; }

    public Instant getNextAttemptAt() { return nextAttemptAt; }
    public void setNextAttemptAt(Instant nextAttemptAt) { this.nextAttemptAt = nextAttemptAt; }

    private String tiempoEvento;
    public String getTiempoEvento() { return tiempoEvento; }
    public void setTiempoEvento(String tiempoEvento) { this.tiempoEvento = tiempoEvento; }

    private int puerta;
    public int getPuerta() { return puerta; }
    public void setPuerta(int puerta) { this.puerta = puerta; }
}
