package org.traccar.api.resource;

import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.traccar.api.BaseResource;
import org.traccar.forwarding.ExternalForwardingManager;
import org.traccar.model.ForwardingGroup;

import java.util.Map;

/**
 * REST API for external forwarding groups.
 *
 * Groups are created automatically by VehicleService when a vehicle is registered
 * with a company. This resource only allows editing the API configuration (endpoint,
 * auth) and viewing/deleting groups.
 *
 * GET    /api/externalforwarding          — list all groups
 * PUT    /api/externalforwarding/{id}     — update API config of a group
 * DELETE /api/externalforwarding/{id}     — delete a group
 */
@Path("externalforwarding")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class ExternalForwardingResource extends BaseResource {

    @Inject
    private ExternalForwardingManager manager;

    @GET
    public Response getAll() {
        return Response.ok(manager.getAll()).build();
    }

    @PUT
    @Path("{id}")
    public Response update(@PathParam("id") String id, ForwardingGroup group) {
        if (group.getName() == null || group.getName().isBlank()) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(Map.of("error", "name is required")).build();
        }
        return manager.update(id, group)
                .map(updated -> Response.ok(updated).build())
                .orElse(Response.status(Response.Status.NOT_FOUND).build());
    }

    @DELETE
    @Path("{id}")
    public Response delete(@PathParam("id") String id) {
        return manager.delete(id)
                ? Response.noContent().build()
                : Response.status(Response.Status.NOT_FOUND).build();
    }
}
