package org.traccar.handler;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.traccar.BaseTest;
import org.traccar.config.Config;
import org.traccar.config.Keys;
import org.traccar.model.Device;
import org.traccar.model.Position;
import org.traccar.session.cache.CacheManager;
import org.traccar.storage.Storage;

import java.util.Date;
import java.util.HashMap;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class FilterHandlerTest extends BaseTest {

    private FilterHandler passingHandler;
    private FilterHandler filteringHandler;

    @BeforeEach
    public void passingHandler() {
        var device = mock(Device.class);
        when(device.getAttributes()).thenReturn(new HashMap<>());
        var config = mock(Config.class);
        var cacheManager = mock(CacheManager.class);
        when(cacheManager.getConfig()).thenReturn(config);
        when(cacheManager.getObject(any(), anyLong())).thenReturn(device);
        passingHandler = new FilterHandler(cacheManager, null, null);
    }

    @BeforeEach
    public void filteringHandler() {
        var device = mock(Device.class);
        when(device.getAttributes()).thenReturn(new HashMap<>());
        var config = mock(Config.class);
        when(config.getString(Keys.FILTER_INVALID.getKey())).thenReturn("true");
        when(config.getString(Keys.FILTER_ZERO.getKey())).thenReturn("true");
        when(config.getString(Keys.FILTER_DUPLICATE.getKey())).thenReturn("true");
        when(config.getString(Keys.FILTER_APPROXIMATE.getKey())).thenReturn("true");
        when(config.getString(Keys.FILTER_STATIC.getKey())).thenReturn("true");
        when(config.getString(Keys.FILTER_DISTANCE.getKey())).thenReturn("10");
        when(config.getString(Keys.FILTER_MAX_SPEED.getKey())).thenReturn("500");
        when(config.getString(Keys.FILTER_SKIP_LIMIT.getKey())).thenReturn("10");
        when(config.getString(Keys.FILTER_SKIP_ATTRIBUTES_ENABLE.getKey())).thenReturn("true");
        when(config.getString(Keys.FILTER_SKIP_ATTRIBUTES.getKey())).thenReturn("alarm,result");
        var cacheManager = mock(CacheManager.class);
        when(cacheManager.getConfig()).thenReturn(config);
        when(cacheManager.getObject(any(), anyLong())).thenReturn(device);
        filteringHandler = new FilterHandler(cacheManager, null, null);
    }

    private Position createPosition(Date time, boolean valid, double speed) {
        Position position = new Position();
        position.setDeviceId(0);
        position.setTime(time);
        position.setValid(valid);
        position.setLatitude(10);
        position.setLongitude(10);
        position.setAltitude(10);
        position.setSpeed(speed);
        position.setCourse(10);
        return position;
    }

    @Test
    public void testFilter() {

        Position position = createPosition(new Date(), true, 10);

        assertFalse(filteringHandler.filter(position));
        assertFalse(passingHandler.filter(position));

        position = createPosition(new Date(Long.MAX_VALUE), true, 10);

        assertTrue(filteringHandler.filter(position));
        assertTrue(passingHandler.filter(position));

        position = createPosition(new Date(), false, 10);

        assertTrue(filteringHandler.filter(position));
        assertFalse(passingHandler.filter(position));

    }

    @Test
    public void testSkipAttributes() {

        Position position = createPosition(new Date(), true, 0);
        position.addAlarm(Position.ALARM_GENERAL);

        assertFalse(filteringHandler.filter(position));

    }

    @Test
    public void testDuplicateStored() throws Exception {

        var device = mock(Device.class);
        when(device.getAttributes()).thenReturn(new HashMap<>());
        var config = mock(Config.class);
        when(config.getString(Keys.FILTER_DUPLICATE_STORED.getKey())).thenReturn("true");
        var cacheManager = mock(CacheManager.class);
        when(cacheManager.getConfig()).thenReturn(config);
        when(cacheManager.getObject(any(), anyLong())).thenReturn(device);

        // la ultima posicion conocida es mas nueva que la entrante (reenvio fuera de orden)
        Position last = createPosition(new Date(2000000), true, 10);
        when(cacheManager.getPosition(anyLong())).thenReturn(last);

        var storage = mock(Storage.class);
        var handler = new FilterHandler(cacheManager, null, storage);

        Position resend = createPosition(new Date(1000000), true, 10);

        // ya existe en BD → se filtra
        when(storage.getObjects(any(), any())).thenReturn(List.of(new Position()));
        assertTrue(handler.filter(resend));

        // no existe en BD (dato historico legitimo) → pasa
        when(storage.getObjects(any(), any())).thenReturn(List.of());
        assertFalse(handler.filter(resend));

        // posicion en vivo (mas nueva que la ultima) → pasa sin consultar BD
        Position live = createPosition(new Date(3000000), true, 10);
        assertFalse(handler.filter(live));

        // un evento de conteo (passengersOn/Off) NUNCA se filtra por duplicateStored,
        // aunque el storage devolviera una coincidencia por fixTime
        Position countingEvent = createPosition(new Date(1000000), true, 0);
        countingEvent.set("passengersOn", 5);
        countingEvent.set("passengersOff", 2);
        when(storage.getObjects(any(), any())).thenReturn(List.of(new Position()));
        assertFalse(handler.filter(countingEvent));

    }

    @Test
    public void testDuplicateNeverFiltersCountingEvents() {

        // filter.duplicate solo compara PRESENCIA de la clave, no su valor: dos eventos de
        // conteo distintos con el mismo fixTime y la misma clave "passengersOn" (valores
        // distintos) se filtraban como si fueran iguales. Los eventos de conteo deben pasar
        // siempre, sin importar el estado de filter.duplicate.
        var device = mock(Device.class);
        when(device.getAttributes()).thenReturn(new HashMap<>());
        var config = mock(Config.class);
        when(config.getString(Keys.FILTER_DUPLICATE.getKey())).thenReturn("true");
        var cacheManager = mock(CacheManager.class);
        when(cacheManager.getConfig()).thenReturn(config);
        when(cacheManager.getObject(any(), anyLong())).thenReturn(device);

        Date time = new Date();
        Position last = createPosition(time, true, 0);
        last.set("passengersOn", 5);
        when(cacheManager.getPosition(anyLong())).thenReturn(last);

        var handler = new FilterHandler(cacheManager, null, null);

        Position event = createPosition(time, true, 0);
        event.set("passengersOn", 8);

        assertFalse(handler.filter(event));
    }

}
