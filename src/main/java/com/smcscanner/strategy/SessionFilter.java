package com.smcscanner.strategy;

import org.springframework.stereotype.Service;
import java.time.*;
import java.util.*;

@Service
public class SessionFilter {
    private static final ZoneId ET = ZoneId.of("America/New_York");
    private static final ZoneId UTC = ZoneId.of("UTC");
    private static final LocalTime NY_OPEN=LocalTime.of(9,30), NY_CLOSE=LocalTime.of(16,0);
    private static final LocalTime CR_OPEN=LocalTime.of(7,0),  CR_CLOSE=LocalTime.of(19,0);

    private record Session(String name,String label,int utcOpen,int utcClose){}
    private static final List<Session> SESSIONS=List.of(
        new Session("London","London Session",8,16),
        new Session("NY","New York Session",14,21),
        new Session("PreMarket","Pre-Market",9,14));

    public boolean isInNySession() { LocalTime n=ZonedDateTime.now(ET).toLocalTime(); return !n.isBefore(NY_OPEN)&&!n.isAfter(NY_CLOSE); }
    public boolean isInCryptoSession() { LocalTime n=ZonedDateTime.now(ET).toLocalTime(); return !n.isBefore(CR_OPEN)&&!n.isAfter(CR_CLOSE); }
    public boolean isInNySession(LocalTime n) { return !n.isBefore(NY_OPEN)&&!n.isAfter(NY_CLOSE); }
    public boolean isInCryptoSession(LocalTime n) { return !n.isBefore(CR_OPEN)&&!n.isAfter(CR_CLOSE); }

    public String sessionName() {
        LocalTime n=ZonedDateTime.now(ET).toLocalTime();
        return sessionName(n);
    }
    public String sessionName(LocalTime n) {
        if (!n.isBefore(LocalTime.of(9,30))&&!n.isAfter(LocalTime.of(10,30))) return "ny_open";
        if (!n.isBefore(LocalTime.of(14,0))&&!n.isAfter(LocalTime.of(16,0)))  return "pm_session";
        return null;
    }
    public String cryptoSessionName() {
        LocalTime n=ZonedDateTime.now(ET).toLocalTime();
        return cryptoSessionName(n);
    }
    public String cryptoSessionName(LocalTime n) {
        if (!n.isBefore(LocalTime.of(7,0))&&!n.isAfter(LocalTime.of(10,30))) return "crypto_morning";
        if (!n.isBefore(LocalTime.of(10,30))&&!n.isAfter(LocalTime.of(16,0))) return "crypto_midday";
        return "crypto_afternoon";
    }
    public boolean isActiveSession() { return !getActiveSessions().isEmpty(); }
    public String currentSession() { List<Map<String,Object>> a=getActiveSessions(); return a.isEmpty()?"None":(String)a.get(0).get("name"); }

    public List<Map<String,Object>> getActiveSessions() {
        ZonedDateTime now=ZonedDateTime.now(UTC);
        double h=now.getHour()+(now.getMinute()/60.0);
        List<Map<String,Object>> active=new ArrayList<>();
        for (Session s:SESSIONS) {
            if (h>=s.utcOpen()&&h<s.utcClose()) {
                double prog=Math.min(100,Math.max(0,(h-s.utcOpen())/(s.utcClose()-s.utcOpen())*100));
                Map<String,Object> m=new HashMap<>();
                m.put("name",s.name()); m.put("label",s.label());
                m.put("progressPct",Math.round(prog*10)/10.0); m.put("remaining",Math.round((s.utcClose()-h)*100)/100.0);
                active.add(m);
            }
        }
        return active;
    }
    public boolean isOverlap() { return getActiveSessions().size()>1; }
}
