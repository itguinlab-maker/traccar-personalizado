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
import org.traccar.model.User;
import org.traccar.storage.StorageException;
import org.traccar.storage.query.Columns;
import org.traccar.storage.query.Condition;
import org.traccar.storage.query.Request;

import java.util.List;
import java.util.Map;

@Path("externalforwarding")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class ExternalForwardingResource extends BaseResource {

    @Inject
    private ExternalForwardingManager manager;

    @GET
    public Response getAll() throws StorageException {
        if (!permissionsService.notAdmin(getUserId())) {
            return Response.ok(manager.getAll()).build();
        }
        String company = getSessionUserCompany();
        if (company == null) {
            return Response.ok(manager.getAll()).build();
        }
        return Response.ok(manager.getByCompany(company)).build();
    }

    @PUT
    @Path("{id}")
    public Response update(@PathParam("id") String id, ForwardingGroup group) throws StorageException {
        if (group.getName() == null || group.getName().isBlank()) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(Map.of("error", "name is required")).build();
        }
        if (!permissionsService.notAdmin(getUserId())) {
            return manager.update(id, group)
                    .map(updated -> Response.ok(updated).build())
                    .orElse(Response.status(Response.Status.NOT_FOUND).build());
        }
        if ("supervisor_global".equals(getSessionUserRole())) {
            return Response.status(Response.Status.FORBIDDEN)
                    .entity(Map.of("error", "Rol de solo lectura")).build();
        }
        String company = getSessionUserCompany();
        if (company != null && !company.equals(group.getName())) {
            return Response.status(Response.Status.FORBIDDEN)
                    .entity(Map.of("error", "Solo puede editar la configuración de su empresa")).build();
        }
        return manager.update(id, group)
                .map(updated -> Response.ok(updated).build())
                .orElse(Response.status(Response.Status.NOT_FOUND).build());
    }

    @DELETE
    @Path("{id}")
    public Response delete(@PathParam("id") String id) throws StorageException {
        if (!permissionsService.notAdmin(getUserId())) {
            return manager.delete(id)
                    ? Response.noContent().build()
                    : Response.status(Response.Status.NOT_FOUND).build();
        }
        if ("supervisor_global".equals(getSessionUserRole())) {
            return Response.status(Response.Status.FORBIDDEN)
                    .entity(Map.of("error", "Rol de solo lectura")).build();
        }
        String company = getSessionUserCompany();
        if (company != null) {
            boolean owned = manager.getByCompany(company).stream().anyMatch(g -> id.equals(g.getId()));
            if (!owned) {
                return Response.status(Response.Status.FORBIDDEN)
                        .entity(Map.of("error", "No tiene permiso para eliminar este grupo")).build();
            }
        }
        return manager.delete(id)
                ? Response.noContent().build()
                : Response.status(Response.Status.NOT_FOUND).build();
    }

    private String getSessionUserCompany() throws StorageException {
        return getSessionUserAttribute("company");
    }

    private String getSessionUserRole() throws StorageException {
        return getSessionUserAttribute("role");
    }

    private String getSessionUserAttribute(String key) throws StorageException {
        List<User> users = storage.getObjects(User.class, new Request(
                new Columns.All(),
                new Condition.Equals("id", getUserId())));
        if (!users.isEmpty()) {
            Object val = users.get(0).getAttributes().get(key);
            return val != null ? val.toString() : null;
        }
        return null;
    }
}
