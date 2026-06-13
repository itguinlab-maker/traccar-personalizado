package org.traccar.hikvision;

public class HikvisionEvent {

    private String id;
    private long deviceId;
    private String dateTime;
    private int channel;
    private int enter;
    private int exit;

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public long getDeviceId() { return deviceId; }
    public void setDeviceId(long deviceId) { this.deviceId = deviceId; }

    public String getDateTime() { return dateTime; }
    public void setDateTime(String dateTime) { this.dateTime = dateTime; }

    public int getChannel() { return channel; }
    public void setChannel(int channel) { this.channel = channel; }

    public int getEnter() { return enter; }
    public void setEnter(int enter) { this.enter = enter; }

    public int getExit() { return exit; }
    public void setExit(int exit) { this.exit = exit; }
}
