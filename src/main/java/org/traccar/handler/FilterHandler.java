/*
 * Copyright 2014 - 2026 Anton Tananaev (anton@traccar.org)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.traccar.handler;

import jakarta.inject.Inject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.traccar.config.Keys;
import org.traccar.database.StatisticsManager;
import org.traccar.helper.UnitsConverter;
import org.traccar.helper.model.AttributeUtil;
import org.traccar.model.Calendar;
import org.traccar.model.Device;
import org.traccar.model.Position;
import org.traccar.session.cache.CacheManager;
import org.traccar.storage.Storage;
import org.traccar.storage.StorageException;
import org.traccar.storage.query.Columns;
import org.traccar.storage.query.Condition;
import org.traccar.storage.query.Request;

import java.util.LinkedList;
import java.util.List;

public class FilterHandler extends BasePositionHandler {

    private static final Logger LOGGER = LoggerFactory.getLogger(FilterHandler.class);

    /** Tolerancia de coordenadas (~1m) para considerar dos posiciones "el mismo punto". */
    private static final double COORDINATE_EPSILON = 0.00001;

    /** Atributos de valor de negocio: si difieren, dos posiciones NUNCA se consideran duplicadas. */
    private static final String[] BUSINESS_ATTRIBUTES = {"passengersOn", "passengersOff"};

    private final CacheManager cacheManager;
    private final StatisticsManager statisticsManager;
    private final Storage storage;

    @Inject
    public FilterHandler(CacheManager cacheManager, StatisticsManager statisticsManager, Storage storage) {
        this.cacheManager = cacheManager;
        this.statisticsManager = statisticsManager;
        this.storage = storage;
    }

    private boolean filterInvalid(Position position) {
        Boolean filterInvalid = AttributeUtil.lookup(cacheManager, Keys.FILTER_INVALID, position.getDeviceId());
        return Boolean.TRUE.equals(filterInvalid) && (!position.getValid()
                || position.getLatitude() > 90 || position.getLongitude() > 180
                || position.getLatitude() < -90 || position.getLongitude() < -180);
    }

    private boolean filterZero(Position position) {
        Boolean filterZero = AttributeUtil.lookup(cacheManager, Keys.FILTER_ZERO, position.getDeviceId());
        return Boolean.TRUE.equals(filterZero) && position.getLatitude() == 0.0 && position.getLongitude() == 0.0;
    }

    /**
     * Compara el VALOR de un atributo (no solo su presencia) entre dos posiciones, normalizando
     * números para que Integer/Long/Double con el mismo valor comparen igual (evita falsos
     * negativos por el tipo numérico que reconstruye el deserializador JSON del storage).
     */
    private boolean attributeEquals(Position a, Position b, String key) {
        Object va = a.getAttributes().get(key);
        Object vb = b.getAttributes().get(key);
        if (va == null || vb == null) {
            return va == vb;
        }
        if (va instanceof Number && vb instanceof Number) {
            return ((Number) va).doubleValue() == ((Number) vb).doubleValue();
        }
        return va.equals(vb);
    }

    /**
     * Determina si dos posiciones son el MISMO evento físico: mismo punto GPS y mismo valor en
     * cada atributo de negocio (conteo de pasajeros). El fixTime se asume ya igual (garantizado
     * por quien llama). Esta es la única condición para tratar algo como "duplicado exacto" —
     * cualquier diferencia, por mínima que sea, significa que es un evento real distinto.
     */
    private boolean isSameEvent(Position a, Position b) {
        if (Math.abs(a.getLatitude() - b.getLatitude()) > COORDINATE_EPSILON
                || Math.abs(a.getLongitude() - b.getLongitude()) > COORDINATE_EPSILON) {
            return false;
        }
        for (String key : BUSINESS_ATTRIBUTES) {
            if (!attributeEquals(a, b, key)) {
                return false;
            }
        }
        return true;
    }

    /**
     * Duplicado inmediato: misma posición cacheada en memoria con el mismo fixTime. Chequeo
     * rápido (sin BD) para el caso común de reenvío en vivo de la última posición.
     */
    private boolean filterDuplicate(Position position, Position last) {
        Boolean filterDuplicate = AttributeUtil.lookup(cacheManager, Keys.FILTER_DUPLICATE, position.getDeviceId());
        if (Boolean.TRUE.equals(filterDuplicate) && last != null && position.getFixTime().equals(last.getFixTime())) {
            return isSameEvent(position, last);
        }
        return false;
    }

    /**
     * Retransmisión/histórico: la posición llega con fixTime igual o anterior a la última
     * conocida. Filosofía: NUNCA se pierde un evento real, sin importar que sea un reenvío.
     *
     *  - Si no hay ninguna posición guardada con ese (deviceId, fixTime): es histórico legítimo
     *    (p.ej. buffer tras pérdida de señal) → se guarda normal, sin marcar nada.
     *  - Si hay una guardada y es EXACTAMENTE el mismo evento (mismo GPS, mismo conteo): es un
     *    reenvío puro del mismo dato (bucle de retransmisión) → se descarta, no aporta nada nuevo.
     *  - Si hay una guardada pero el contenido difiere en algo (otro GPS, otro conteo): es un
     *    evento real distinto que casualmente comparte fixTime → se guarda igual, marcado con el
     *    atributo "retransmitted" para trazabilidad, NUNCA se descarta.
     */
    private boolean filterDuplicateStored(Position position, Position last) {
        Boolean filterDuplicateStored = AttributeUtil.lookup(
                cacheManager, Keys.FILTER_DUPLICATE_STORED, position.getDeviceId());
        if (Boolean.TRUE.equals(filterDuplicateStored)
                && last != null && !position.getFixTime().after(last.getFixTime())) {
            try {
                List<Position> existing = storage.getObjects(Position.class, new Request(
                        new Columns.All(),
                        new Condition.And(
                                new Condition.Equals("deviceId", position.getDeviceId()),
                                new Condition.Equals("fixTime", position.getFixTime()))));
                if (existing.isEmpty()) {
                    return false;
                }
                boolean exactDuplicate = existing.stream().anyMatch(stored -> isSameEvent(position, stored));
                if (exactDuplicate) {
                    return true;
                }
                position.set("retransmitted", true);
                return false;
            } catch (StorageException e) {
                LOGGER.warn("DuplicateStored check failed: {}", e.getMessage());
            }
        }
        return false;
    }

    private boolean filterOutdated(Position position) {
        Boolean filterOutdated = AttributeUtil.lookup(cacheManager, Keys.FILTER_OUTDATED, position.getDeviceId());
        return Boolean.TRUE.equals(filterOutdated) && position.getOutdated();
    }

    private boolean filterFuture(Position position) {
        Long filterFuture = AttributeUtil.lookup(cacheManager, Keys.FILTER_FUTURE, position.getDeviceId());
        return filterFuture != null
                && position.getFixTime().getTime() > System.currentTimeMillis() + filterFuture * 1000;
    }

    private boolean filterPast(Position position) {
        Long filterPast = AttributeUtil.lookup(cacheManager, Keys.FILTER_PAST, position.getDeviceId());
        return filterPast != null && position.getFixTime().getTime() < System.currentTimeMillis() - filterPast * 1000;
    }

    private boolean filterAccuracy(Position position) {
        Integer filterAccuracy = AttributeUtil.lookup(cacheManager, Keys.FILTER_ACCURACY, position.getDeviceId());
        return filterAccuracy != null && position.getAccuracy() > filterAccuracy;
    }

    private boolean filterApproximate(Position position) {
        Boolean filterApproximate = AttributeUtil.lookup(cacheManager, Keys.FILTER_APPROXIMATE, position.getDeviceId());
        return Boolean.TRUE.equals(filterApproximate) && position.getBoolean(Position.KEY_APPROXIMATE);
    }

    private boolean filterStatic(Position position) {
        Boolean filterStatic = AttributeUtil.lookup(cacheManager, Keys.FILTER_STATIC, position.getDeviceId());
        return Boolean.TRUE.equals(filterStatic) && position.getSpeed() == 0.0;
    }

    private boolean filterDistance(Position position, Position last) {
        Integer filterDistance = AttributeUtil.lookup(cacheManager, Keys.FILTER_DISTANCE, position.getDeviceId());
        if (filterDistance != null && last != null) {
            return position.getDouble(Position.KEY_DISTANCE) < filterDistance;
        }
        return false;
    }

    private boolean filterMaxSpeed(Position position, Position last) {
        Integer filterMaxSpeed = AttributeUtil.lookup(cacheManager, Keys.FILTER_MAX_SPEED, position.getDeviceId());
        if (filterMaxSpeed != null && last != null) {
            double distance = position.getDouble(Position.KEY_DISTANCE);
            double time = position.getFixTime().getTime() - last.getFixTime().getTime();
            return time > 0 && UnitsConverter.knotsFromMps(distance / (time / 1000)) > filterMaxSpeed;
        }
        return false;
    }

    private boolean filterMinPeriod(Position position, Position last) {
        Integer filterMinPeriod = AttributeUtil.lookup(cacheManager, Keys.FILTER_MIN_PERIOD, position.getDeviceId());
        if (filterMinPeriod != null && last != null) {
            long time = position.getFixTime().getTime() - last.getFixTime().getTime();
            return time > 0 && time < filterMinPeriod * 1000L;
        }
        return false;
    }

    private boolean filterDailyLimit(Position position, Position last) {
        long deviceId = position.getDeviceId();
        Integer filterDailyLimit = AttributeUtil.lookup(cacheManager, Keys.FILTER_DAILY_LIMIT, deviceId);
        if (filterDailyLimit != null && statisticsManager.messageStoredCount(deviceId) >= filterDailyLimit) {
            Integer filterDailyLimitInterval = AttributeUtil.lookup(
                    cacheManager, Keys.FILTER_DAILY_LIMIT_INTERVAL, deviceId);
            long lastTime = last != null ? last.getFixTime().getTime() : 0;
            long interval = position.getFixTime().getTime() - lastTime;
            return filterDailyLimitInterval == null || interval < filterDailyLimitInterval * 1000L;
        }
        return false;
    }

    private boolean skipLimit(Position position, Position last) {
        Long skipLimit = AttributeUtil.lookup(cacheManager, Keys.FILTER_SKIP_LIMIT, position.getDeviceId());
        if (skipLimit != null && last != null) {
            return (position.getServerTime().getTime() - last.getServerTime().getTime()) > skipLimit * 1000;
        }
        return false;
    }

    private boolean skipAttributes(Position position) {
        Boolean skipAttributes = AttributeUtil.lookup(
                cacheManager, Keys.FILTER_SKIP_ATTRIBUTES_ENABLE, position.getDeviceId());
        if (Boolean.TRUE.equals(skipAttributes)) {
            String string = AttributeUtil.lookup(cacheManager, Keys.FILTER_SKIP_ATTRIBUTES, position.getDeviceId());
            if (string != null) {
                for (String attribute : string.split("[ ,]")) {
                    if (position.hasAttribute(attribute)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    protected boolean filter(Position position) {

        List<String> filterTypes = new LinkedList<>();

        // filter out invalid data
        if (filterInvalid(position)) {
            filterTypes.add("Invalid");
        }
        if (filterZero(position)) {
            filterTypes.add("Zero");
        }
        if (filterOutdated(position)) {
            filterTypes.add("Outdated");
        }
        if (filterFuture(position)) {
            filterTypes.add("Future");
        }
        if (filterPast(position)) {
            filterTypes.add("Past");
        }
        if (filterAccuracy(position)) {
            filterTypes.add("Accuracy");
        }
        if (filterApproximate(position)) {
            filterTypes.add("Approximate");
        }

        // filter out excessive data
        long deviceId = position.getDeviceId();
        Position last = cacheManager.getPosition(deviceId);
        if (filterDuplicate(position, last) && !skipLimit(position, last) && !skipAttributes(position)) {
            filterTypes.add("Duplicate");
        }
        if (filterDuplicateStored(position, last) && !skipLimit(position, last) && !skipAttributes(position)) {
            filterTypes.add("DuplicateStored");
        }
        if (filterStatic(position) && !skipLimit(position, last) && !skipAttributes(position)) {
            filterTypes.add("Static");
        }
        if (filterDistance(position, last) && !skipLimit(position, last) && !skipAttributes(position)) {
            filterTypes.add("Distance");
        }
        if (filterMaxSpeed(position, last)) {
            filterTypes.add("MaxSpeed");
        }
        if (filterMinPeriod(position, last)) {
            filterTypes.add("MinPeriod");
        }
        if (filterDailyLimit(position, last)) {
            filterTypes.add("DailyLimit");
        }

        Device device = cacheManager.getObject(Device.class, deviceId);
        if (device.getCalendarId() > 0) {
            Calendar calendar = cacheManager.getObject(Calendar.class, device.getCalendarId());
            if (!calendar.checkMoment(position.getFixTime())) {
                filterTypes.add("Calendar");
            }
        }

        if (!filterTypes.isEmpty()) {
            LOGGER.info("Position filtered by {} filters from device: {}",
                    String.join(" ", filterTypes), device.getUniqueId());
            return true;
        }

        if (Boolean.TRUE.equals(position.getAttributes().get("retransmitted"))) {
            LOGGER.info("Position marked as retransmitted (stored anyway) from device: {}", device.getUniqueId());
        }

        return false;
    }

    @Override
    public void onPosition(Position position, Callback callback) {
        callback.processed(filter(position));
    }

}
