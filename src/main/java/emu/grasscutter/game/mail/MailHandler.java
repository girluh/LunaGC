package emu.grasscutter.game.mail;

import emu.grasscutter.Grasscutter;
import emu.grasscutter.database.DatabaseHelper;
import emu.grasscutter.game.player.*;
import emu.grasscutter.server.event.player.PlayerReceiveMailEvent;
import emu.grasscutter.server.packet.send.*;
import java.util.*;

public class MailHandler extends BasePlayerManager {
    private final List<Mail> mail;

    public MailHandler(Player player) {
        super(player);

        this.mail = new ArrayList<>();
    }

    public List<Mail> getMail() {
        return mail;
    }

    // ---------------------MAIL------------------------

    public void sendMail(Mail message) {
        // Call mail receive event.
        PlayerReceiveMailEvent event = new PlayerReceiveMailEvent(this.getPlayer(), message);
        event.call();
        if (event.isCanceled()) return;
        message = event.getMessage();

        message.setOwnerUid(this.getPlayer().getUid());
        message.save();

        this.mail.add(message);

        Grasscutter.getLogger()
                .debug(
                        "Mail sent to user ["
                                + this.getPlayer().getUid()
                                + ":"
                                + this.getPlayer().getNickname()
                                + "]!");

        if (this.getPlayer().isOnline()) {
            this.getPlayer().sendPacket(new PacketMailChangeNotify(this.getPlayer(), message));
        } // TODO: setup a way for the mail notification to show up when someone receives mail when they
        // were offline
    }

    public boolean deleteMail(int mailId) {
        Mail message = getMailById(mailId);

        if (message != null) {
            this.getMail().remove(mailId - 1);
            message.expireTime = 0;
            message.save();

            return true;
        }

        return false;
    }

    public void deleteMail(List<Integer> mailList) {
        List<Integer> sortedMailList = new ArrayList<>();
        sortedMailList.addAll(mailList);
        Collections.sort(sortedMailList, Collections.reverseOrder());

        List<Integer> deleted = new ArrayList<>();

        for (int id : sortedMailList) {
            if (this.deleteMail(id)) {
                deleted.add(id);
            }
        }

        player.getSession().send(new PacketDelMailRsp(player, deleted));
        player.getSession().send(new PacketMailChangeNotify(player, null, deleted));
    }

    /** Client-facing mail ids start at 1 (index + 1); mail_id 0 is not a valid mail. */
    public Mail getMailById(int mailId) {
        int index = mailId - 1;
        if (index < 0 || index >= this.mail.size()) return null;
        return this.mail.get(index);
    }

    public int getMailIndex(Mail message) {
        return this.mail.indexOf(message);
    }

    public boolean replaceMailByIndex(int mailId, Mail message) {
        if (getMailById(mailId) != null) {
            this.mail.set(mailId - 1, message);
            message.save();
            return true;
        } else {
            return false;
        }
    }

    public void loadFromDatabase() {
        List<Mail> mailList = DatabaseHelper.getAllMail(this.getPlayer());

        for (Mail mail : mailList) {
            this.getMail().add(mail);
        }
    }
}
