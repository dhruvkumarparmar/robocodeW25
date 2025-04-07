/*
 * Copyright (c) 2001-2023 Mathew A. Nelson and Robocode contributors
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * https://robocode.sourceforge.io/license/epl-v10.html
 */
package roborumble;

import net.sf.robocode.roborumble.battlesengine.BattlesRunner;
import net.sf.robocode.roborumble.battlesengine.PrepareBattles;
import net.sf.robocode.roborumble.netengine.BotsDownload;
import net.sf.robocode.roborumble.netengine.ResultsUpload;
import net.sf.robocode.roborumble.netengine.UpdateRatingFiles;

import static net.sf.robocode.roborumble.util.PropertiesUtil.getProperties;

import java.io.File;
import java.io.IOException;
import java.util.Map;
import java.util.Properties;

/**
 * Implements the client side of RoboRumble@Home.
 * Controlled by properties files.
 *
 * @author Albert Pérez (original)
 * @author Flemming N. Larsen (contributor)
 * @author Jerome Lavigne (contributor)
 * @author Pavel Savara (contributor)
 */
public class RoboRumbleAtHome {

    public static void main(String[] args) {

        // Get the associated parameters file
        String paramsFileName;
        String envParams = System.getenv("PARAMS");
        if (args.length >= 1) {
            paramsFileName = args[0];
        } else if (envParams != null) {
            paramsFileName = envParams;
        } else {
            paramsFileName = "./roborumble/roborumble.txt";
            System.out.println("No argument found specifying properties file. \"" + paramsFileName + "\" assumed.");
        }

        // Path Traversal Mitigation
        try {
            File file = new File(paramsFileName);
            File canonicalFile = file.getCanonicalFile();
            File baseDir = new File("./roborumble").getCanonicalFile();

            if (!canonicalFile.getPath().startsWith(baseDir.getPath())) {
                throw new SecurityException("Blocked path traversal attempt: " + canonicalFile.getPath());
            }

            // Normalize param path after check
            paramsFileName = canonicalFile.getPath();
        } catch (IOException | SecurityException ex) {
            System.err.println("Invalid properties file path: " + ex.getMessage());
            return;
        }

        // Read parameters for running the app
        Properties properties = getProperties(paramsFileName);

        String envUser = System.getenv("RUMBLE_USER");
        if (envUser != null) {
            properties.setProperty("USER", envUser);
        }
        String envParticipantsUrl = System.getenv("RUMBLE_PARTICIPANTSURL");
        if (envParticipantsUrl != null && !envParticipantsUrl.equals("null")) {
            properties.setProperty("PARTICIPANTSURL", envParticipantsUrl);
        }
        String envUpdateBotsUrl = System.getenv("RUMBLE_UPDATEBOTSURL");
        if (envUpdateBotsUrl != null && !envUpdateBotsUrl.equals("null")) {
            properties.setProperty("UPDATEBOTSURL", envUpdateBotsUrl);
        }
        String envResultsUrl = System.getenv("RUMBLE_RESULTSURL");
        if (envResultsUrl != null && !envResultsUrl.equals("null")) {
            properties.setProperty("RESULTSURL", envResultsUrl);
        }
        String envRatingsUrl = System.getenv("RUMBLE_RATINGSURL");
        if (envRatingsUrl != null && !envRatingsUrl.equals("null")) {
            properties.setProperty("RATINGS.URL", envRatingsUrl);
        }

        String downloads = properties.getProperty("DOWNLOAD", "NOT");
        String executes = properties.getProperty("EXECUTE", "NOT");
        String uploads = properties.getProperty("UPLOAD", "NOT");
        String iterates = properties.getProperty("ITERATE", "NOT");
        String runonly = properties.getProperty("RUNONLY", "GENERAL");
        String melee = properties.getProperty("MELEE", "NOT");

        int iterations = 0;
        long lastdownload = 0;
        boolean ratingsdownloaded = false;
        boolean participantsdownloaded;
        String version = null;
        String game = paramsFileName;
        while (game.indexOf("/") != -1) {
            game = game.substring(game.indexOf("/") + 1);
        }
        game = game.substring(0, game.indexOf("."));

        do {
            final BattlesRunner engine = new BattlesRunner(game, properties);

            if (version == null) {
                version = engine.getVersion();
            }

            System.out.println("Iteration number " + iterations);

            if (downloads.equals("YES")) {
                BotsDownload download = new BotsDownload(game, properties);

                if (runonly.equals("SERVER")) {
                    ratingsdownloaded = download.downloadRatings();
                }
                if ((System.currentTimeMillis() - lastdownload) > 10 * 60 * 1000) {
                    participantsdownloaded = download.downloadParticipantsList();
                    System.out.println("Downloading missing bots ...");
                    download.downloadMissingBots();
                    download.updateCodeSize();

                    if (ratingsdownloaded && participantsdownloaded) {
                        System.out.println("Removing old participants from server ...");
                        download.notifyServerForOldParticipants();
                    }

                    lastdownload = System.currentTimeMillis();
                }
            }

            if (executes.equals("YES")) {
                final boolean isMelee = melee.equals("YES");

                boolean ready;
                PrepareBattles battles = new PrepareBattles(paramsFileName);

                if (isMelee) {
                    System.out.println("Preparing melee battles list ...");
                    ready = battles.createMeleeBattlesList();
                } else {
                    final boolean isSmartBattles = ratingsdownloaded && runonly.equals("SERVER");

                    if (isSmartBattles) {
                        System.out.println("Preparing battles list using smart battles...");
                        ready = battles.createSmartBattlesList();
                    } else {
                        System.out.println("Preparing battles list...");
                        ready = battles.createBattlesList();
                    }
                }

                System.setProperty("PARALLEL", "false");
                System.setProperty("RANDOMSEED", "none");

                if (ready) {
                    if (isMelee) {
                        System.out.println("Executing melee battles ...");
                    } else {
                        System.out.println("Executing battles ...");
                    }

                    engine.runBattlesImpl(isMelee);
                }
            }

            if (uploads.equals("YES") && version != null) {
                System.out.println("Uploading results ...");
                ResultsUpload upload = new ResultsUpload(game, properties, version);
                upload.uploadResults();

                System.out.println("Updating number of battles fought ...");
                UpdateRatingFiles updater = new UpdateRatingFiles(game, properties);

                ratingsdownloaded = updater.updateRatings();
            }

            iterations++;
        } while (iterates.equals("YES"));
    }
}
