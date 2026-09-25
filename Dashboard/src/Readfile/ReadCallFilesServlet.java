package Readfile;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.Types;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TimeZone;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads Asterisk ".call" files from a spool directory,
 * parses call/retry/campaign details,
 * inserts one database row for every retry,
 * and deletes the processed file only after successful DB insertion.
 */
public class ReadCallFilesServlet {

    public static void main(String[] args) {

        String localDir =
                "/var/spool/asterisk/outgoing_done";

        // Linux:
        // String localDir = "/var/spool/asterisk/outgoing_done";

        File folder = new File(localDir);

        File[] files = folder.listFiles();

        if (files == null) {

            System.out.println(
                    "Directory not found or empty: " + localDir
            );

            return;
        }

        for (File file : files) {

            // -------------------------------------------------
            // Process only .call files
            // -------------------------------------------------

            if (!file.isFile()
                    || !file.getName().toLowerCase().endsWith(".call")) {

                continue;
            }

            try {

                // -------------------------------------------------
                // 1. READ FILE
                // -------------------------------------------------

                String fileContent = readFile(file);

                System.out.println();
                System.out.println(
                        "Reading file: " + file.getName()
                );

                System.out.println(
                        "========================================"
                );

                System.out.println(fileContent);

                System.out.println(
                        "========================================"
                );


                // -------------------------------------------------
                // 2. PARSE FILE
                // -------------------------------------------------

                Map<String, String> fields =
                        parseCallFile(fileContent);


                // -------------------------------------------------
                // 3. INSERT INTO DATABASE
                // -------------------------------------------------

                boolean inserted =
                        insertIntoDB(fields);


                // -------------------------------------------------
                // 4. DELETE ONLY IF DATABASE INSERT WAS SUCCESSFUL
                // -------------------------------------------------

                if (inserted) {

                    if (file.delete()) {

                        System.out.println(
                                "Deleted: " + file.getName()
                        );

                    } else {

                        System.out.println(
                                "Failed to delete: "
                                        + file.getName()
                        );
                    }

                } else {

                    System.out.println(
                            "File retained because DB insertion failed: "
                                    + file.getName()
                    );
                }


            } catch (Exception e) {

                System.out.println(
                        "Error processing file: "
                                + file.getName()
                );

                e.printStackTrace();
            }
        }


        System.out.println();
        System.out.println(
                "Processing completed."
        );
    }


    // =============================================================
    // READ FILE
    // =============================================================

    private static String readFile(File file)
            throws Exception {

        ByteArrayOutputStream baos =
                new ByteArrayOutputStream();

        try (FileInputStream fis =
                     new FileInputStream(file)) {

            byte[] buffer =
                    new byte[1024];

            int read;

            while ((read = fis.read(buffer)) != -1) {

                baos.write(
                        buffer,
                        0,
                        read
                );
            }
        }

        return baos.toString();
    }


    // =============================================================
    // RETRY RECORD
    // =============================================================

    static class RetryRecord {

        int retryNo;

        String startRetry;

        String endRetry;


        RetryRecord(
                int retryNo,
                String startRetry,
                String endRetry) {

            this.retryNo = retryNo;
            this.startRetry = startRetry;
            this.endRetry = endRetry;
        }
    }


    // =============================================================
    // PARSE CALL FILE
    // =============================================================

    private static Map<String, String> parseCallFile(
            String content) {


        Map<String, String> data =
                new LinkedHashMap<>();


        // ---------------------------------------------------------
        // Store retries by ACTUAL retry number
        //
        // Example:
        //
        // Retry 1 -> Start + End
        // Retry 2 -> Start + End
        // Retry 3 -> Start only
        // ---------------------------------------------------------

        Map<Integer, RetryRecord> retryMap =
                new LinkedHashMap<>();


        // ---------------------------------------------------------
        // Date formatter
        // ---------------------------------------------------------

        SimpleDateFormat sdf =
                new SimpleDateFormat(
                        "yyyy-MM-dd HH:mm:ss"
                );

        sdf.setTimeZone(
                TimeZone.getTimeZone(
                        "Asia/Kolkata"
                )
        );


        // ---------------------------------------------------------
        // Regex for StartRetry
        //
        // Example:
        //
        // StartRetry: 1877133 1 (1790061586)
        //
        // Group 1 = Retry number
        // Group 2 = Epoch
        // ---------------------------------------------------------

        Pattern startRetryPattern =
                Pattern.compile(
                        "StartRetry:\\s+\\d+\\s+(\\d+)\\s+\\((\\d{10})\\)",
                        Pattern.CASE_INSENSITIVE
                );


        // ---------------------------------------------------------
        // Regex for EndRetry
        //
        // Example:
        //
        // EndRetry: 1877133 1 (1790061614)
        //
        // Group 1 = Retry number
        // Group 2 = Epoch
        // ---------------------------------------------------------

        Pattern endRetryPattern =
                Pattern.compile(
                        "EndRetry:\\s+\\d+\\s+(\\d+)\\s+\\((\\d{10})\\)",
                        Pattern.CASE_INSENSITIVE
                );


        // =========================================================
        // READ EACH LINE
        // =========================================================

        for (String line :
                content.split("\\r?\\n")) {


            line = line.trim();


            // =====================================================
            // CHANNEL
            // =====================================================

            if (line.regionMatches(
                    true,
                    0,
                    "Channel:",
                    0,
                    "Channel:".length())) {


                String channel =
                        line.substring(
                                line.indexOf(":") + 1
                        ).trim();


                System.out.println(
                        "Channel Raw : " + channel
                );


                // -------------------------------------------------
                // Expected:
                //
                // PJSIP/09360424058@out
                // -------------------------------------------------

                int slashIndex =
                        channel.indexOf("/");

                int atIndex =
                        channel.indexOf("@");


                if (slashIndex >= 0
                        && atIndex > slashIndex) {


                    String number =
                            channel.substring(
                                    slashIndex + 1,
                                    atIndex
                            ).trim();


                    data.put(
                            "Channel",
                            number
                    );


                    System.out.println(
                            "Channel Number : "
                                    + number
                    );

                } else {

                    // Fallback if channel format is unexpected

                    data.put(
                            "Channel",
                            channel
                    );
                }
            }


            // =====================================================
            // STATUS
            // =====================================================

            else if (line.regionMatches(
                    true,
                    0,
                    "Status:",
                    0,
                    "Status:".length())) {


                String status =
                        line.substring(
                                line.indexOf(":") + 1
                        ).trim();


                data.put(
                        "Status",
                        status
                );


                System.out.println(
                        "Status : " + status
                );
            }


            // =====================================================
            // EXTENSION
            // =====================================================

            else if (line.regionMatches(
                    true,
                    0,
                    "Extension:",
                    0,
                    "Extension:".length())) {


                String extension =
                        line.substring(
                                line.indexOf(":") + 1
                        ).trim();


                data.put(
                        "Extension",
                        extension
                );


                System.out.println(
                        "Extension : " + extension
                );
            }


            // =====================================================
            // CAMPAIGN
            //
            // Example:
            //
            // setvar:Campaign=Recharge_Alert
            //
            // Case insensitive
            // =====================================================

            else if (line.regionMatches(
                    true,
                    0,
                    "Setvar:Campaign=",
                    0,
                    "Setvar:Campaign=".length())) {


                String campaign =
                        line.substring(
                                line.indexOf("=") + 1
                        ).trim();


                // Campaign ID
                data.put(
                        "campaign_id",
                        campaign
                );


                // -------------------------------------------------
                // You said Campaign ID and Campaign Name are
                // the same value.
                //
                // Therefore store the same value for both.
                // -------------------------------------------------

                data.put(
                        "campaign_name",
                        campaign
                );


                System.out.println(
                        "Campaign ID   : "
                                + campaign
                );

                System.out.println(
                        "Campaign Name : "
                                + campaign
                );
            }


            // =====================================================
            // OPTIONAL CAMPAIGN NAME
            //
            // If the file contains:
            //
            // Setvar:Campaign_Name=Recharge_Alert
            //
            // this will override the previous value.
            // =====================================================

            else if (line.regionMatches(
                    true,
                    0,
                    "Setvar:Campaign_Name=",
                    0,
                    "Setvar:Campaign_Name=".length())) {


                String campaignName =
                        line.substring(
                                line.indexOf("=") + 1
                        ).trim();


                data.put(
                        "campaign_name",
                        campaignName
                );


                System.out.println(
                        "Campaign Name : "
                                + campaignName
                );
            }


            // =====================================================
            // START RETRY
            //
            // Example:
            //
            // StartRetry: 1877133 1 (1790061586)
            //
            // We extract:
            //
            // Retry No = 1
            // Epoch    = 1790061586
            // =====================================================

            else if (line.regionMatches(
                    true,
                    0,
                    "StartRetry:",
                    0,
                    "StartRetry:".length())) {


                Matcher matcher =
                        startRetryPattern.matcher(line);


                if (matcher.find()) {


                    int retryNo =
                            Integer.parseInt(
                                    matcher.group(1)
                            );


                    long epoch =
                            Long.parseLong(
                                    matcher.group(2)
                            );


                    String startTime =
                            sdf.format(
                                    new Date(
                                            epoch * 1000L
                                    )
                            );


                    // -------------------------------------------------
                    // Check whether this retry already exists
                    // -------------------------------------------------

                    RetryRecord retry =
                            retryMap.get(retryNo);


                    if (retry == null) {


                        retry =
                                new RetryRecord(
                                        retryNo,
                                        startTime,
                                        null
                                );


                        retryMap.put(
                                retryNo,
                                retry
                        );

                    } else {

                        retry.startRetry =
                                startTime;
                    }


                    System.out.println(
                            "StartRetry -> Retry "
                                    + retryNo
                                    + " : "
                                    + startTime
                    );
                }
            }


            // =====================================================
            // END RETRY
            //
            // Example:
            //
            // EndRetry: 1877133 1 (1790061614)
            //
            // Retry 1 is matched using retry number 1.
            // =====================================================

            else if (line.regionMatches(
                    true,
                    0,
                    "EndRetry:",
                    0,
                    "EndRetry:".length())) {


                Matcher matcher =
                        endRetryPattern.matcher(line);


                if (matcher.find()) {


                    int retryNo =
                            Integer.parseInt(
                                    matcher.group(1)
                            );


                    long epoch =
                            Long.parseLong(
                                    matcher.group(2)
                            );


                    String endTime =
                            sdf.format(
                                    new Date(
                                            epoch * 1000L
                                    )
                            );


                    RetryRecord retry =
                            retryMap.get(retryNo);


                    if (retry == null) {


                        retry =
                                new RetryRecord(
                                        retryNo,
                                        null,
                                        endTime
                                );


                        retryMap.put(
                                retryNo,
                                retry
                        );

                    } else {

                        retry.endRetry =
                                endTime;
                    }


                    System.out.println(
                            "EndRetry -> Retry "
                                    + retryNo
                                    + " : "
                                    + endTime
                    );
                }
            }


            // =====================================================
            // DELAYED RETRY
            //
            // Do NOT create a database retry row.
            //
            // Example:
            //
            // DelayedRetry: 1877133 0 (...)
            //
            // This is not a new retry attempt.
            // =====================================================

            else if (line.regionMatches(
                    true,
                    0,
                    "DelayedRetry:",
                    0,
                    "DelayedRetry:".length())) {


                System.out.println(
                        "DelayedRetry ignored : "
                                + line
                );
            }
        }


        // =========================================================
 
        StringBuilder retryData =
                new StringBuilder();


        for (RetryRecord retry :
                retryMap.values()) {


            if (retryData.length() > 0) {

                retryData.append(";");

            }


            retryData
                    .append(retry.retryNo)
                    .append("|")
                    .append(
                            retry.startRetry == null
                                    ? ""
                                    : retry.startRetry
                    )
                    .append("|")
                    .append(
                            retry.endRetry == null
                                    ? ""
                                    : retry.endRetry
                    );
        }


        data.put(
                "retryData",
                retryData.toString()
        );


        // =========================================================
        // DEBUG
        // =========================================================

        System.out.println();
        System.out.println(
                "========== PARSED DATA =========="
        );

        System.out.println(
                "Channel       : "
                        + data.get("Channel")
        );

        System.out.println(
                "Status        : "
                        + data.get("Status")
        );

        System.out.println(
                "Campaign ID   : "
                        + data.get("campaign_id")
        );

        System.out.println(
                "Campaign Name : "
                        + data.get("campaign_name")
        );

        System.out.println(
                "Extension     : "
                        + data.get("Extension")
        );

        System.out.println(
                "Retry Data    : "
                        + data.get("retryData")
        );

        System.out.println(
                "================================="
        );


        return data;
    }


    // =============================================================
    // INSERT INTO DATABASE
    // =============================================================

    private static boolean insertIntoDB(
            Map<String, String> data) {


        // ---------------------------------------------------------
        // SQL SERVER CONNECTION
        // ---------------------------------------------------------

       
        String jdbcUrl =
                "jdbc:sqlserver://192.168.5.57:1433;"
                + "databaseName=NASSIT_DB;"
                + "encrypt=True;"
                + "trustServerCertificate=true;";

        String dbUser = "sa";

        String dbPass = "Muruga275";

        // =========================================================
        // SQL
        //
        // ONE ROW = ONE RETRY
        // =========================================================

        String sql =
                "INSERT INTO TBL_CAMPAIGN_REPORT "
                        + "([Channel], "
                        + "[Status], "
                        + "[campaign_id], "
                        + "[campaign_name], "
                        + "[Retry_No], "
                        + "[Start_Retry], "
                        + "[End_Retry], "
                        + "[Extension]) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?)";


        // =========================================================
        // DATABASE CONNECTION
        // =========================================================

        try (
                Connection conn =
                        DriverManager.getConnection(
                                jdbcUrl,
                                dbUser,
                                dbPass
                        );

                PreparedStatement stmt =
                        conn.prepareStatement(sql)
        ) {


            // -----------------------------------------------------
            // Get retry data
            // -----------------------------------------------------

            String retryData =
                    data.get("retryData");


            // =====================================================
            // NO RETRY
            //
            // We still insert one row for the call.
            //
            // Retry_No       = NULL
            // Start_Retry    = NULL
            // End_Retry      = NULL
            // =====================================================

            if (retryData == null
                    || retryData.trim().isEmpty()) {


                System.out.println(
                        "No retry found."
                );


                setCommonFields(
                        stmt,
                        data
                );


                stmt.setNull(
                        5,
                        Types.INTEGER
                );


                stmt.setNull(
                        6,
                        Types.TIMESTAMP
                );


                stmt.setNull(
                        7,
                        Types.TIMESTAMP
                );


                stmt.setString(
                        8,
                        data.get("Extension")
                );


                stmt.executeUpdate();


                System.out.println(
                        "DB Inserted - No Retry"
                );


                return true;
            }


            // =====================================================
            // RETRIES FOUND
            //
            // Example retryData:
            //
            // 1|2026-06-22 10:10:00|2026-06-22 10:10:28;
            // 2|2026-06-22 10:10:32|2026-06-22 10:11:01;
            // 3|2026-06-22 10:11:05|
            // =====================================================

            String[] retries =
                    retryData.split(";");


            int insertedCount = 0;


            for (String retry :
                    retries) {


                if (retry.trim().isEmpty()) {

                    continue;
                }


                String[] parts =
                        retry.split(
                                "\\|",
                                -1
                        );


                if (parts.length < 3) {


                    System.out.println(
                            "Invalid retry data: "
                                    + retry
                    );


                    continue;
                }


                // -------------------------------------------------
                // RETRY NUMBER
                // -------------------------------------------------

                int retryNo =
                        Integer.parseInt(
                                parts[0]
                        );


                // -------------------------------------------------
                // START RETRY
                // -------------------------------------------------

                String startRetry =
                        parts[1].trim();


                // -------------------------------------------------
                // END RETRY
                // -------------------------------------------------

                String endRetry =
                        parts[2].trim();


                // =================================================
                // COMMON FIELDS
                // =================================================

                setCommonFields(
                        stmt,
                        data
                );


                // =================================================
                // RETRY NUMBER
                // =================================================

                stmt.setInt(
                        5,
                        retryNo
                );


                // =================================================
                // START RETRY
                // =================================================

                if (startRetry.isEmpty()) {


                    stmt.setNull(
                            6,
                            Types.TIMESTAMP
                    );

                } else {


                    stmt.setTimestamp(
                            6,
                            java.sql.Timestamp.valueOf(
                                    startRetry
                            )
                    );
                }


                // =================================================
                // END RETRY
                // =================================================

                if (endRetry.isEmpty()) {


                    stmt.setNull(
                            7,
                            Types.TIMESTAMP
                    );

                } else {


                    stmt.setTimestamp(
                            7,
                            java.sql.Timestamp.valueOf(
                                    endRetry
                            )
                    );
                }


                // =================================================
                // EXTENSION
                // =================================================

                stmt.setString(
                        8,
                        data.get("Extension")
                );


                // =================================================
                // INSERT
                // =================================================

                stmt.executeUpdate();


                insertedCount++;


                // =================================================
                // LOG
                // =================================================

                System.out.println(
                        "DB Inserted - Retry "
                                + retryNo
                );


                System.out.println(
                        "   Start : "
                                + startRetry
                );


                System.out.println(
                        "   End   : "
                                + (
                                endRetry.isEmpty()
                                        ? "NULL"
                                        : endRetry
                        )
                );
            }


            // =====================================================
            // CHECK
            // =====================================================

            if (insertedCount == 0) {


                System.out.println(
                        "No retry rows were inserted."
                );


                return false;
            }


            System.out.println();
            System.out.println(
                    "========================================"
            );


            System.out.println(
                    "Total retry rows inserted : "
                            + insertedCount
            );


            System.out.println(
                    "Channel       : "
                            + data.get("Channel")
            );


            System.out.println(
                    "Status        : "
                            + data.get("Status")
            );


            System.out.println(
                    "Campaign ID   : "
                            + data.get("campaign_id")
            );


            System.out.println(
                    "Campaign Name : "
                            + data.get("campaign_name")
            );


            System.out.println(
                    "========================================"
            );


            return true;


        } catch (Exception e) {


            System.out.println(
                    "Database insertion failed."
            );


            e.printStackTrace();


            return false;
        }
    }


    // =============================================================
    // SET COMMON DATABASE FIELDS
    // =============================================================

    private static void setCommonFields(
            PreparedStatement stmt,
            Map<String, String> data)
            throws Exception {


        // ---------------------------------------------------------
        // 1. CHANNEL
        // ---------------------------------------------------------

        stmt.setString(
                1,
                data.getOrDefault(
                        "Channel",
                        ""
                )
        );


        // ---------------------------------------------------------
        // 2. STATUS
        // ---------------------------------------------------------

        stmt.setString(
                2,
                data.getOrDefault(
                        "Status",
                        ""
                )
        );


        // ---------------------------------------------------------
        // 3. CAMPAIGN ID
        // ---------------------------------------------------------

        stmt.setString(
                3,
                data.getOrDefault(
                        "campaign_id",
                        ""
                )
        );


        // ---------------------------------------------------------
        // 4. CAMPAIGN NAME
        // ---------------------------------------------------------

        stmt.setString(
                4,
                data.getOrDefault(
                        "campaign_name",
                        ""
                )
        );
    }
}