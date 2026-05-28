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
import org.traccar.vehicle.VehicleRecord;
import org.traccar.vehicle.VehicleService;

import java.util.Map;

/**
 * CRUD REST API for vehicle records.
 *
 * GET    /api/vehiclerecords                    — all records
 * GET    /api/vehiclerecords/device/{deviceId}  — record for a specific device
 * POST   /api/vehiclerecords                    — create
 * PUT    /api/vehiclerecords/{id}               — update
 * DELETE /api/vehiclerecords/{id}               — delete
 *
 * Required field: plate. company and deviceId are optional (0 = no linked device).
 */
@Path("vehiclerecords")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class VehicleResource extends BaseResource {

    @Inject
    private VehicleService service;

    @GET
    public Response getAll() {
        return Response.ok(service.getAll()).build();
    }

    @GET
    @Path("device/{deviceId}")
    public Response getByDevice(@PathParam("deviceId") long deviceId) {
        return service.getByDeviceId(deviceId)
                .map(r -> Response.ok(r).build())
                .orElse(Response.status(Response.Status.NOT_FOUND).build());
    }

    @POST
    public Response create(VehicleRecord record) {
        Response validation = validateRequired(record);
        if (validation != null) {
            return validation;
        }
        return Response.ok(service.create(record)).build();
    }

    @PUT
    @Path("{id}")
    public Response update(@PathParam("id") String id, VehicleRecord record) {
        Response validation = validateRequired(record);
        if (validation != null) {
            return validation;
        }
        return service.update(id, record)
                .map(updated -> Response.ok(updated).build())
                .orElse(Response.status(Response.Status.NOT_FOUND).build());
    }

    @DELETE
    @Path("{id}")
    public Response delete(@PathParam("id") String id) {
        return service.delete(id)
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
}
