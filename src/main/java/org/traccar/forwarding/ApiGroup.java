package org.traccar.forwarding;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

public class ApiGroup {

    private String id;
    private String nombreEmpresa;
    private String endpointUrl;
    private String metodoHttp = "POST";

    /**
     * Auth configuration. Supported structures:
     *   {"tipo":"Basic",  "usuario":"u", "contrasena":"p"}
     *   {"tipo":"Bearer", "token":"t"}
     *   {} or {"tipo":"None"}  → no auth
     */
    private Map<String, String> configAuth = new HashMap<>();

    /** Extra HTTP headers injected on every request. */
    private Map<String, String> headersExtra = new HashMap<>();

    /** Traccar device IDs that belong to this group. */
    private Set<Long> deviceIds = new HashSet<>();

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getNombreEmpresa() { return nombreEmpresa; }
    public void setNombreEmpresa(String nombreEmpresa) { this.nombreEmpresa = nombreEmpresa; }

    public String getEndpointUrl() { return endpointUrl; }
    public void setEndpointUrl(String endpointUrl) { this.endpointUrl = endpointUrl; }

    public String getMetodoHttp() { return metodoHttp; }
    public void setMetodoHttp(String metodoHttp) {
        this.metodoHttp = (metodoHttp != null) ? metodoHttp.toUpperCase() : "POST";
    }

    public Map<String, String> getConfigAuth() { return configAuth; }
    public void setConfigAuth(Map<String, String> configAuth) {
        this.configAuth = configAuth != null ? configAuth : new HashMap<>();
    }

    public Map<String, String> getHeadersExtra() { return headersExtra; }
    public void setHeadersExtra(Map<String, String> headersExtra) {
        this.headersExtra = headersExtra != null ? headersExtra : new HashMap<>();
    }

    public Set<Long> getDeviceIds() { return deviceIds; }
    public void setDeviceIds(Set<Long> deviceIds) {
        this.deviceIds = deviceIds != null ? deviceIds : new HashSet<>();
    }
}
