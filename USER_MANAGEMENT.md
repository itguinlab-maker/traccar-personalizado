# Gestión de Usuarios y Roles — CountinG&KLAB

Este documento describe el sistema de roles, cómo crear usuarios por empresa y qué puede ver/hacer cada rol.

---

## 1. Arquitectura del sistema multiempresa

La plataforma está diseñada para servir a **varias empresas de transporte simultáneamente**, con separación total de datos entre ellas.

```
SuperAdmin (plataforma)
└── Empresa TRSC  ← Grupo Traccar "TRSC"
│   ├── Dispositivos GPS asignados al grupo
│   ├── Vehículos registrados con company = "TRSC"
│   ├── AdminEmpresa_TRSC  → ve solo lo de TRSC
│   ├── Supervisor_TRSC    → ve solo lo de TRSC
│   └── Auditor_TRSC       → reportes de TRSC, sin editar
└── Empresa METRO ← Grupo Traccar "METRO"
    ├── Dispositivos GPS asignados al grupo
    ├── Vehículos registrados con company = "METRO"
    └── AdminEmpresa_METRO → ve solo lo de METRO
```

La separación está respaldada por **dos capas**:
1. **Traccar nativo** — `GET /api/devices`, `GET /api/groups`, `/api/reports/*` filtran automáticamente según los permisos del usuario en la base de datos.
2. **APIs custom** — `GET /api/vehiclerecords` y `GET /api/externalforwarding` filtran por el atributo `company` del usuario.

---

## 2. Roles disponibles

| Rol | Valor en `user.attributes.role` | Descripción |
|---|---|---|
| **SuperAdmin** | *(user.administrator = true)* | Acceso total a toda la plataforma y todas las empresas |
| **Admin de Empresa** | `admin_empresa` | Gestiona vehículos, datos y usuarios de su empresa |
| **Supervisor** | `supervisor` | Ve vehículos y reportes de su empresa, sin editar |
| **Propietario** | `propietario` | Ve solo los dispositivos asignados explícitamente a él |
| **Auditor** | `auditor` | Solo reportes, sin mapa ni configuración |

---

## 3. Permisos por rol

### 3.1 Menú Configuración

| Ítem | SuperAdmin | Admin Empresa | Supervisor | Propietario | Auditor |
|---|:---:|:---:|:---:|:---:|:---:|
| Preferencias | ✅ | ✅ | ✅ | ✅ | ✅ |
| Mi usuario | ✅ | ✅ | ✅ | ✅ | ✅ |
| Dispositivos | ✅ | ✅ | ❌ | ❌ | ❌ |
| **Vehículos** | ✅ | ✅ | 👁️ solo ver | ❌ | ❌ |
| Geocercas | ✅ | ✅ | ✅ | ❌ | ❌ |
| **Grupos** | ✅ | ❌ | ❌ | ❌ | ❌ |
| Conductores, Calendarios… | ✅ | ✅ | ✅ | ❌ | ❌ |
| Usuarios (gestión) | ✅ | ✅* | ❌ | ❌ | ❌ |
| Configuración del servidor | ✅ | ❌ | ❌ | ❌ | ❌ |

*\* Admin de Empresa puede gestionar usuarios que él mismo creó (si tiene `userLimit > 0`).*

### 3.2 Menú Reportes

| Ítem | SuperAdmin | Admin Empresa | Supervisor | Propietario | Auditor |
|---|:---:|:---:|:---:|:---:|:---:|
| Conteo General | ✅ | ✅ empresa | ✅ empresa | ✅ asignados | ✅ |
| Streamax Eventos | ✅ | ✅ empresa | ✅ empresa | ✅ asignados | ✅ |
| Hikvision Eventos | ✅ | ✅ empresa | ✅ empresa | ✅ asignados | ✅ |
| **Envío Externo** | ✅ | ✅ empresa | ❌ | ❌ | ❌ |
| Reportes estándar | ✅ | ✅ | ✅ | ✅ | ✅* |
| Estadísticas / Auditoría | ✅ | ❌ | ❌ | ❌ | ❌ |

*\* Auditor tiene `readonly = true`, no puede modificar nada.*

### 3.3 Registro de Vehículos

| Acción | SuperAdmin | Admin Empresa | Supervisor | Propietario | Auditor |
|---|:---:|:---:|:---:|:---:|:---:|
| Ver vehículos | ✅ todos | ✅ empresa | ✅ empresa | ❌ | ❌ |
| Crear vehículo | ✅ | ✅ empresa | ❌ | ❌ | ❌ |
| Editar vehículo | ✅ | ✅ empresa | ❌ | ❌ | ❌ |
| Eliminar vehículo | ✅ | ✅ empresa | ❌ | ❌ | ❌ |
| Descargar vídeo | ✅ | ✅ | ✅ | ✅ | ❌ |

---

## 4. Configuración inicial (SuperAdmin)

### Paso 1 — Crear el grupo de la empresa

En Traccar: **Configuración → Grupos → Nuevo grupo**

- Nombre: exactamente el nombre de la empresa (ej. `TRSC`)
- Este nombre será el identificador de empresa en todo el sistema

### Paso 2 — Crear los dispositivos GPS

En Traccar: **Configuración → Dispositivos → Nuevo dispositivo**

El dispositivo quedará sin grupo hasta que se registre el vehículo correspondiente.

### Paso 3 — Crear el usuario de la empresa

En Traccar: **Configuración → Usuarios → Nuevo usuario**

Campos obligatorios:
- Nombre, email, contraseña

En el acordeón **"Empresa y Rol"** (visible solo para SuperAdmin):
- **Rol**: seleccionar el rol apropiado (ej. `Admin de Empresa`)
- **Empresa**: escribir el nombre exacto del grupo (ej. `TRSC`)

En el acordeón **"Permisos"**:
- **User Limit**: si el admin de empresa va a poder crear sus propios sub-usuarios, definir aquí el máximo (ej. `5`)
- Dejar `Administrator` desmarcado

### Paso 4 — Asignar permisos Traccar al usuario

En **Configuración → Usuarios → [usuario] → Conexiones**:

- Vincular el usuario al **grupo** `TRSC`

Esto hace que `GET /api/devices` y todos los reportes nativos de Traccar solo devuelvan los dispositivos de ese grupo.

> **Importante:** vincular al grupo (no a dispositivos individuales) asegura que nuevos vehículos incorporados al grupo sean visibles automáticamente.

### Paso 5 — Registrar los vehículos

En **Configuración → Vehículos → Nuevo vehículo**:

- Seleccionar el grupo/empresa en el desplegable *(solo muestra grupos existentes)*
- Vincular el dispositivo GPS correspondiente
- Al guardar, el dispositivo queda automáticamente asignado al grupo Traccar

### Paso 6 — (Opcional) Configurar Envío Externo

Si la empresa integra con una API externa para recibir eventos de conteo:

En **Reportes → Envío Externo de Conteo**:
- El grupo de la empresa ya aparece en la lista (se crea al registrar el primer vehículo, solo cuando es admin quien lo hace)
- Configurar URL, usuario y contraseña de la API destino

---

## 5. Cómo funciona la separación de datos

### Dispositivos (Traccar nativo)

```
Traccar DB: tc_user_group
  userId=42 → groupId=7 (grupo "TRSC")

GET /api/devices  →  solo devuelve dispositivos con groupId=7
GET /api/reports/route  →  solo posiciones de esos dispositivos
```

El usuario con rol `admin_empresa` nunca puede ver dispositivos de otra empresa porque Traccar filtra a nivel de base de datos.

### Vehículos (API custom)

```
VehicleResource.getAll()
  → si admin: devuelve todos
  → si no: lee user.attributes.company del usuario
           filtra vehicle_records.json por company == ese valor
```

### Forwarding Groups (API custom)

```
ExternalForwardingResource.getAll()
  → si admin: devuelve todos los grupos de forwarding
  → si no: devuelve solo el grupo cuyo nombre == user.attributes.company
```

### Auto-asignación de dispositivos a grupos

Al crear o editar un vehículo:
```
VehicleService.syncDeviceGroup(deviceId, company)
  → busca grupo Traccar con name == company
  → si existe: actualiza device.groupId = ese grupo
  → si no existe: log de advertencia, no hace nada
```

El grupo Traccar debe existir previamente (creado por el admin).

### Auto-creación de Forwarding Groups

```
syncCompanyGroup(company, deviceIds, createIfMissing)
  → createIfMissing=true  solo cuando adminMode=true (usuario admin)
  → createIfMissing=false para no-admin: solo actualiza existentes
```

---

## 6. Atributos del usuario

Los roles y la empresa se almacenan en `user.attributes`:

```json
{
  "role": "admin_empresa",
  "company": "TRSC"
}
```

Estos atributos se guardan en la tabla `tc_users` de PostgreSQL junto con el usuario.

El SuperAdmin los configura desde **Configuración → Usuarios → [usuario] → Empresa y Rol**.

---

## 7. Casos de uso frecuentes

### Agregar un nuevo bus a empresa existente

1. Crear dispositivo GPS (SuperAdmin)
2. Ir a **Configuración → Vehículos** (como admin_empresa o SuperAdmin)
3. Nuevo vehículo → seleccionar grupo `TRSC` → vincular dispositivo
4. El bus queda visible para todos los usuarios de TRSC automáticamente

### Cambiar empresa a un vehículo

1. Editar el vehículo y seleccionar el nuevo grupo en el desplegable
2. El dispositivo cambia de grupo automáticamente
3. El vehículo desaparece del forwarding del grupo anterior y aparece en el nuevo

### Crear un supervisor para una empresa

1. Nuevo usuario (SuperAdmin o AdminEmpresa con userLimit > 0)
2. Acordeón Empresa y Rol → Rol: `Supervisor`, Empresa: `TRSC`
3. Conexiones → vincular al grupo `TRSC`
4. El supervisor ve reportes y vehículos pero no puede editar ni crear

### Usuario que solo ve sus vehículos asignados (Propietario)

1. Nuevo usuario → Rol: `Propietario`, Empresa: `TRSC`
2. Conexiones → vincular a los **dispositivos específicos** (no al grupo)
3. El propietario ve solo esos vehículos en el mapa y reportes

---

## 8. Referencia rápida de endpoints con filtrado

| Endpoint | Admin | No-admin con empresa |
|---|---|---|
| `GET /api/devices` | Todos | Solo grupo asignado (Traccar nativo) |
| `GET /api/groups` | Todos | Solo grupos asignados (Traccar nativo) |
| `GET /api/reports/route` | Todos | Solo dispositivos accesibles |
| `GET /api/vehiclerecords` | Todos | Filtrado por `company` del usuario |
| `GET /api/externalforwarding` | Todos | Filtrado por `company` del usuario |
| `POST /api/vehiclerecords` | Cualquier empresa | Solo su empresa |
| `PUT /api/vehiclerecords/{id}` | Cualquier empresa | Solo su empresa |
| `DELETE /api/vehiclerecords/{id}` | Cualquier registro | Solo registros de su empresa |

---

*Última actualización: Junio 2026*
