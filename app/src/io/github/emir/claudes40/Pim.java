package io.github.emir.claudes40;

import javax.microedition.pim.Event;
import javax.microedition.pim.EventList;
import javax.microedition.pim.PIM;
import javax.microedition.pim.PIMException;
import javax.microedition.pim.PIMItem;
import javax.microedition.pim.PIMList;
import javax.microedition.pim.ToDo;
import javax.microedition.pim.ToDoList;

/**
 * Writes one entry the user confirmed in CalendarForm to the phone's own
 * calendar or to-do list (JSR 75 PIM). With Files, the only class that uses
 * JSR 75 (tools/check.py); callers check ClaudeS40MIDlet.hasPim() first.
 * Blocks and may make the phone ask for permission: worker threads only.
 * Fields the phone's list does not support are left out.
 */
final class Pim {

    private Pim() {
    }

    /**
     * Adds an event (one hour long) or a to-do. alarmMinutes: minutes before
     * the start, or -1 for none (events only). Returns null on success, or a
     * reason for the user.
     */
    static String add(boolean todo, String title, long when, int alarmMinutes) {
        PIMList list = null;
        try {
            if (todo) {
                ToDoList l = (ToDoList) PIM.getInstance().openPIMList(PIM.TODO_LIST, PIM.READ_WRITE);
                list = l;
                ToDo t = l.createToDo();
                if (l.isSupportedField(ToDo.SUMMARY)) {
                    t.addString(ToDo.SUMMARY, PIMItem.ATTR_NONE, title);
                } else if (l.isSupportedField(ToDo.NOTE)) {
                    t.addString(ToDo.NOTE, PIMItem.ATTR_NONE, title);
                }
                if (l.isSupportedField(ToDo.DUE)) {
                    t.addDate(ToDo.DUE, PIMItem.ATTR_NONE, when);
                }
                t.commit();
            } else {
                EventList l = (EventList) PIM.getInstance().openPIMList(PIM.EVENT_LIST, PIM.READ_WRITE);
                list = l;
                Event e = l.createEvent();
                if (l.isSupportedField(Event.SUMMARY)) {
                    e.addString(Event.SUMMARY, PIMItem.ATTR_NONE, title);
                } else if (l.isSupportedField(Event.NOTE)) {
                    e.addString(Event.NOTE, PIMItem.ATTR_NONE, title);
                }
                e.addDate(Event.START, PIMItem.ATTR_NONE, when);
                if (l.isSupportedField(Event.END)) {
                    e.addDate(Event.END, PIMItem.ATTR_NONE, when + 60L * 60 * 1000);
                }
                if (alarmMinutes >= 0 && l.isSupportedField(Event.ALARM)) {
                    e.addInt(Event.ALARM, PIMItem.ATTR_NONE, alarmMinutes * 60);
                }
                e.commit();
            }
            return null;
        } catch (PIMException e) {
            return L.s("Telefon kaydı kabul etmedi: ", "The phone did not accept it: ") + e.getMessage();
        } catch (SecurityException e) {
            return L.s("İzin verilmedi. Takvime ekleme için telefonun sorusuna 'Evet' deyin.",
                    "Permission denied. Answer 'Yes' when the phone asks, to add it.");
        } catch (RuntimeException e) {
            return L.s("Eklenemedi: ", "Could not add it: ") + e.getClass().getName() + ": " + e.getMessage();
        } finally {
            if (list != null) {
                try {
                    list.close();
                } catch (PIMException e) {
                    // ignore
                }
            }
        }
    }
}
