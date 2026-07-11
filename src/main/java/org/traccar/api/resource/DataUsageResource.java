package org.traccar.api.resource;

import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import org.traccar.api.BaseResource;
import org.traccar.datausage.DataUsageManager;
import org.traccar.datausage.DeviceDataUsage;
import org.traccar.model.Device;
import org.traccar.storage.StorageException;
import org.traccar.storage.query.Columns;
import org.traccar.storage.query.Condition;
import org.traccar.storage.query.Request;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Estado de SIM: datos transmitidos por cada dispositivo a la plataforma.
 */
@Path("datausage")
@Produces(MediaType.APPLICATION_JSON)
public class DataUsageResource extends BaseResource {

    @Inject
    private DataUsageManager dataUsageManager;

    @GET
    public List<Map<String, Object>> get() throws StorageException {
        String currentMonth = LocalDate.now(ZoneOffset.UTC).toString().substring(0, 7);
        List<Map<String, Object>> result = new ArrayList<>();
        for (DeviceDataUsage usage : dataUsageManager.getAll()) {
            Device device;
            try {
                permissionsService.checkPermission(Device.class, getUserId(), usage.getDeviceId());
                device = storage.getObject(Device.class, new Request(
                        new Columns.All(),
                        new Condition.Equals("id", usage.getDeviceId())));
            } catch (SecurityException e) {
                continue;
            }
            Map<String, Object> item = new HashMap<>();
            item.put("deviceId", usage.getDeviceId());
            item.put("name", device != null ? device.getName() : String.valueOf(usage.getDeviceId()));
            item.put("uniqueId", device != null ? device.getUniqueId() : "");
            item.put("status", device != null ? device.getStatus() : "unknown");
            item.put("lastUpdate", device != null ? device.getLastUpdate() : null);
            item.put("lastReceived", usage.getLastReceived());
            item.put("totalBytes", usage.getTotalBytes());
            item.put("totalMessages", usage.getTotalMessages());
            item.put("monthBytes", usage.getMonthlyBytes().getOrDefault(currentMonth, 0L));
            item.put("monthlyBytes", usage.getMonthlyBytes());
            result.add(item);
        }
        return result;
    }
}
