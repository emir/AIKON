package io.github.emir.claudes40;

import java.util.Calendar;
import java.util.Date;

import javax.microedition.lcdui.ChoiceGroup;
import javax.microedition.lcdui.Command;
import javax.microedition.lcdui.CommandListener;
import javax.microedition.lcdui.DateField;
import javax.microedition.lcdui.Displayable;
import javax.microedition.lcdui.Form;
import javax.microedition.lcdui.Gauge;
import javax.microedition.lcdui.TextField;

/**
 * "Takvime ekle" / "Add to calendar": a form prefilled from a message (from
 * Claude's entry line if there is one, else the start of the message and the
 * next full hour). The user checks it; only "Kaydet" writes it, on a worker
 * thread, to the phone's calendar or to-do list (Pim).
 */
final class CalendarForm implements CommandListener, Runnable {

    private static final int[] ALARMS = { -1, 0, 15, 60, 24 * 60 };

    private final ClaudeS40MIDlet midlet;
    private final Displayable back;
    private final Form form;
    private final TextField title;
    private final ChoiceGroup kind;
    private final DateField when;
    private final ChoiceGroup alarm;
    private final Command saveCmd = new Command(L.t("Save"), Command.OK, 1);
    private final Command cancelCmd = new Command(L.t("Cancel"), Command.BACK, 1);
    private boolean saving;

    // what run() writes
    private boolean todo;
    private String what;
    private long at;
    private int alarmMinutes;

    CalendarForm(ClaudeS40MIDlet midlet, ChatSession.Entry e, Displayable back) {
        this.midlet = midlet;
        this.back = back;
        Cal c = Cal.parse(e.text);
        form = new Form(L.t("Add to calendar"));
        title = new TextField(L.t("Title"), c != null ? c.title : ClaudeS40MIDlet.quote(e.text), 100,
                TextField.ANY);
        kind = new ChoiceGroup(L.t("Where"), ChoiceGroup.EXCLUSIVE,
                new String[] { L.t("Calendar"), L.t("To-do list") }, null);
        kind.setSelectedIndex(c != null && c.todo ? 1 : 0, true);
        when = new DateField(L.t("Date and time"), DateField.DATE_TIME);
        when.setDate(new Date(c != null ? c.when : nextHour()));
        alarm = new ChoiceGroup(L.t("Alarm (calendar)"), ChoiceGroup.EXCLUSIVE,
                new String[] { L.t("None"), L.t("At the start"), L.t("15 minutes before"),
                    L.t("1 hour before"), L.t("1 day before") }, null);
        alarm.setSelectedIndex(2, true);
        form.append(title);
        form.append(kind);
        form.append(when);
        form.append(alarm);
        form.addCommand(saveCmd);
        form.addCommand(cancelCmd);
        form.setCommandListener(this);
    }

    void show() {
        midlet.display().setCurrent(form);
    }

    private static long nextHour() {
        Calendar c = Calendar.getInstance();
        c.setTime(new Date(System.currentTimeMillis() + 60L * 60 * 1000));
        c.set(Calendar.MINUTE, 0);
        c.set(Calendar.SECOND, 0);
        c.set(Calendar.MILLISECOND, 0);
        return c.getTime().getTime();
    }

    public void commandAction(Command cmd, Displayable d) {
        if (cmd == cancelCmd) {
            midlet.display().setCurrent(back);
            return;
        }
        if (cmd != saveCmd) {
            return;
        }
        String t = title.getString().trim();
        Date date = when.getDate();
        if (t.length() == 0 || date == null) {
            midlet.info(L.t("A title and a date are needed."), form);
            return;
        }
        synchronized (this) {
            if (saving) {
                return;
            }
            saving = true;
            todo = kind.getSelectedIndex() == 1;
            what = t;
            at = date.getTime();
            int a = alarm.getSelectedIndex();
            alarmMinutes = a >= 0 && a < ALARMS.length ? ALARMS[a] : -1;
        }
        // the phone's own running bar while the entry is written (it may ask for permission first)
        savingBar = new Gauge(L.t("Saving..."), false, Gauge.INDEFINITE, Gauge.CONTINUOUS_RUNNING);
        form.append(savingBar);
        new Thread(this).start();
    }

    /** The running bar while saving, or null. */
    private Gauge savingBar;

    public void run() {
        boolean t;
        String w;
        long a;
        int m;
        synchronized (this) {
            t = todo;
            w = what;
            a = at;
            m = alarmMinutes;
        }
        String err = Pim.add(t, w, a, m);
        synchronized (this) {
            saving = false;
            for (int i = form.size() - 1; i >= 0; i--) {
                if (form.get(i) == savingBar) {
                    form.delete(i);
                }
            }
            savingBar = null;
        }
        if (err != null) {
            midlet.info(err, form);
        } else {
            midlet.info((t ? L.t("Added to the to-do list: ") + Text.local(a, false)
                    : L.t("Added to the calendar: ") + Text.local(a, true)) + "\n" + w, back);
        }
    }
}
