package org.example;

import net.dv8tion.jda.api.entities.Guild;

import java.util.UUID;

public class BackgroundThread extends Thread {

    Guild guild;

    public BackgroundThread(Guild guild) {
        this.guild = guild;
    }

    public void run() {
        while (true) {
            try {
                String id = UUID.randomUUID().toString().substring(0, 31);
                guild.getSelfMember().modifyNickname(id).queue();
                sleep(30000);
            } catch (InterruptedException e) {
            }
        }
    }
}
