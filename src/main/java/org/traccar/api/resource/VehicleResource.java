package org.traccar.api.resource;

import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.traccar.api.BaseResource;
import org.traccar.model.User;
import org.traccar.storage.StorageException;
import org.traccar.storage.query.Columns;
import org.traccar.storage.query.Condition;
import org.traccar.storage.query.Request;
import org.traccar.vehicle.VehicleRecord;
import org.traccar.vehicle.VehicleService;

import java.util.List;
import java.util.Map;

@Path("vehiclerecords")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class VehicleResource extends BaseResource {

    @Inject
    private VehicleService service;

    @GET
    public Response getAll() throws StorageException {
        if (!permissionsService.notAdmin(getUserId())) {
            return Response.ok(service.getAll()).build();
        }
        String company = getSessionUserCompany();
        if (company == null) {
            return Response.ok(service.getAll()).build();
        }
        return Response.ok(service.getByCompany(company)).build();
    }

    @GET
    @Path("device/{deviceId}")
    public Response getByDevice(@PathParam("deviceId") long deviceId) {
        return service.getByDeviceId(deviceId)
                .map(r -> Response.ok(r).build())
                .orElse(Response.status(Response.Status.NOT_FOUND).build());
    }

    @POST
    public Response create(VehicleRecord record) throws StorageException {
        Response validation = validateRequired(record);
        if (validation != null) {
            return validation;
        }
        boolean isAdmin = !permissionsService.notAdmin(getUserId());
        if (!isAdmin) {
            String company = getSessionUserCompany();
            if (company != null && !company.equals(record.getCompany())) {
                return Response.status(Response.Status.FORBIDDEN)
                        .entity(Map.of("error", "Solo puede crear vehículos de su empresa")).build();
            }
        }
        return Response.ok(service.create(record, isAdmin)).build();
    }

    @PUT
    @Path("{id}")
    public Response update(@PathParam("id") String id, VehicleRecord record) throws StorageException {
        Response validation = validateRequired(record);
        if (validation != null) {
            return validation;
        }
        boolean isAdmin = !permissionsService.notAdmin(getUserId());
        if (!isAdmin) {
            String company = getSessionUserCompany();
            if (company != null && !company.equals(record.getCompany())) {
                return Response.status(Response.Status.FORBIDDEN)
                        .entity(Map.of("error", "Solo puede editar vehículos de su empresa")).build();
            }
        }
        return service.update(id, record, isAdmin)
                .map(updated -> Response.ok(updated).build())
                .orElse(Response.status(Response.Status.NOT_FOUND).build());
    }

    @DELETE
    @Path("{id}")
    public Response delete(@PathParam("id") String id) throws StorageException {
        boolean isAdmin = !permissionsService.notAdmin(getUserId());
        if (!isAdmin) {
            String company = getSessionUserCompany();
            if (company != null) {
                boolean owned = service.getByCompany(company).stream().anyMatch(r -> id.equals(r.getId()));
                if (!owned) {
                    return Response.status(Response.Status.FORBIDDEN)
                            .entity(Map.of("error", "No tiene permiso para eliminar este vehículo")).build();
                }
            }
        }
        return service.delete(id, isAdmin)
                ? Response.noContent().build()
                : Response.status(Response.Status.NOT_FOUND).build();
    }

    private Response validateRequired(VehicleRecord record) {
        if (record.getPlate() == null || record.getPlate().isBlank()) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(Map.of("error", "plate is required")).build();
        }
        return null;
    }

    private String getSessionUserCompany() throws StorageException {
        List<User> users = storage.getObjects(User.class, new Request(
                new Columns.All(),
                new Condition.Equals("id", getUserId())));
        if (!users.isEmpty()) {
            Object val = users.get(0).getAttributes().get("company");
            return val != null ? val.toString() : null;
        }
        return null;
    }
}
