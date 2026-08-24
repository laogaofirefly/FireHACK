/*
 * This file is part of the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

import { getMcVersion } from "./mc_version.js"

const buildNumber = process.argv[2];
const branch = process.argv[3];
const compareUrl = process.argv[4];
const success = process.argv[5] === "true";
const webhookUrl = process.env.DISCORD_WEBHOOK;

if (!webhookUrl || !/^https?:\/\//.test(webhookUrl)) {
    console.warn("Skipping Discord webhook: DISCORD_WEBHOOK is missing or invalid");
    process.exit(0);
}

const mcVersion = await getMcVersion();

function sendDiscordWebhook() {
    const compareRequest = compareUrl && /^https?:\/\//.test(compareUrl)
        ? fetch(compareUrl).then(res => res.ok ? res.json() : { commits: [] })
        : Promise.resolve({ commits: [] });
    compareRequest
        .then(res => {
            let description = "";

            description += "**Branch:** " + branch;
            description += "\n**Status:** " + (success ? "success" : "failure");

            let changes = "\n\n**Changes:**";
            let hasChanges = false;
            for (let i in res.commits) {
                let commit = res.commits[i];

                changes += "\n- [`" + commit.sha.substring(0, 7) + "`](https://github.com/MeteorDevelopment/meteor-client/commit/" + commit.sha + ") *" + commit.commit.message + "*";
                hasChanges = true;
            }
            if (hasChanges) description += changes;

            if (success) {
                description += "\n\nVisit our [website](https://meteorclient.com) for download";
            }

            const webhook = {
                username: "Builds",
                avatar_url: "https://meteorclient.com/icon.png",
                embeds: [
                    {
                        title: "Meteor Client " + mcVersion + " build #" + buildNumber,
                        description: description,
                        url: "https://meteorclient.com",
                            color: success ? 2672680 : 13117480
                    }
                ]
            };

            fetch(webhookUrl, {
                method: "POST",
                headers: {
                    "Content-Type": "application/json"
                },
                body: JSON.stringify(webhook)
            });
        });
}

if (success) {
    fetch("https://meteorclient.com/api/recheckMaven", {
        method: "POST",
        headers: {
            "Authorization": process.env.SERVER_TOKEN
        }
    });
}

sendDiscordWebhook()
