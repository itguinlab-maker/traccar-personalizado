package org.traccar.forwarding;

import java.time.Instant;

public class IntegrationLog {

    private Instant timestamp;
    private String groupId;
    private String nombreEmpresa;
    private long deviceId;
    private String placa;
    private Integer statusCode;  // null on network error
    private boolean success;
    private String error;
    private int attempt;
    private String tiempoEvento; // ISO-8601 UTC, from the payload's "tiempo_evento"
    private int puerta;          // door number (1-indexed), from the payload's "puerta"

    public Instant getTimestamp() { return timestamp; }
    public void setTimestamp(Instant timestamp) { this.timestamp = timestamp; }

    public String getGroupId() { return groupId; }
    public void setGroupId(String groupId) { this.groupId = groupId; }

    public String getNombreEmpresa() { return nombreEmpresa; }
    public void setNombreEmpresa(String nombreEmpresa) { this.nombreEmpresa = nombreEmpresa; }

    public long getDeviceId() { return deviceId; }
    public void setDeviceId(long deviceId) { this.deviceId = deviceId; }

    public String getPlaca() { return placa; }
    public void setPlaca(String placa) { this.placa = placa; }

    public Integer getStatusCode() { return statusCode; }
    public void setStatusCode(Integer statusCode) { this.statusCode = statusCode; }

    public boolean isSuccess() { return success; }
    public void setSuccess(boolean success) { this.success = success; }

    public String getError() { return error; }
    public void setError(String error) { this.error = error; }

    public int getAttempt() { return attempt; }
    public void setAttempt(int attempt) { this.attempt = attempt; }

    public String getTiempoEvento() { return tiempoEvento; }
    public void setTiempoEvento(String tiempoEvento) { this.tiempoEvento = tiempoEvento; }

    public int getPuerta() { return puerta; }
    public void setPuerta(int puerta) { this.puerta = puerta; }
}
