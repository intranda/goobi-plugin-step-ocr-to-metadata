package de.intranda.goobi.plugins;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

/**
 * This file is part of a plugin for Goobi - a Workflow tool for the support of mass digitization.
 *
 * Visit the websites for more information.
 *          - https://goobi.io
 *          - https://www.intranda.com
 *          - https://github.com/intranda/goobi
 *
 * This program is free software; you can redistribute it and/or modify it under the terms of the GNU General Public License as published by the Free
 * Software Foundation; either version 2 of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE. See the GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License along with this program; if not, write to the Free Software Foundation, Inc., 59
 * Temple Place, Suite 330, Boston, MA 02111-1307 USA
 *
 */

import java.util.HashMap;
import java.util.List;

import org.apache.commons.configuration.SubnodeConfiguration;
import org.goobi.beans.Process;
import org.goobi.beans.Step;
import org.goobi.production.enums.LogType;
import org.goobi.production.enums.PluginGuiType;
import org.goobi.production.enums.PluginReturnValue;
import org.goobi.production.enums.PluginType;
import org.goobi.production.enums.StepReturnValue;
import org.goobi.production.plugin.interfaces.IStepPluginVersion2;

import de.sub.goobi.config.ConfigPlugins;
import de.sub.goobi.helper.Helper;
import de.sub.goobi.helper.StorageProvider;
import de.sub.goobi.helper.StorageProviderInterface;
import de.sub.goobi.helper.exceptions.SwapException;
import lombok.Getter;
import lombok.extern.log4j.Log4j2;
import net.xeoh.plugins.base.annotations.PluginImplementation;

@PluginImplementation
@Log4j2
public class OcrToMetadataStepPlugin implements IStepPluginVersion2 {
    private static StorageProviderInterface storageProvider = StorageProvider.getInstance();

    @Getter
    private String title = "intranda_step_ocr_to_metadata";
    @Getter
    private Step step;
    private Process process;
    @Getter
    private String value;
    @Getter
    private String metadataField;
    private String returnPath;

    // text value that will be read from the ocr folder and saved to the mets file
    private String textValue = null;
    // true if ocr text folder is available, false otherwise
    private boolean ocrTextFolderAvailable;

    @Override
    public void initialize(Step step, String returnPath) {
        this.returnPath = returnPath;
        this.step = step;

        process = this.step.getProzess();

        // read parameters from correct block in configuration file
        SubnodeConfiguration myconfig = ConfigPlugins.getProjectAndStepConfig(title, step);
        value = myconfig.getString("value", "default value");
        metadataField = myconfig.getString("metadataField");
        log.debug("metadataField = " + metadataField);
        log.info("OcrToMetadata step plugin initialized");
    }

    @Override
    public PluginGuiType getPluginGuiType() {
        return PluginGuiType.NONE;
    }

    @Override
    public String getPagePath() {
        return "/uii/plugin_step_ocr_to_metadata.xhtml";
    }

    @Override
    public PluginType getType() {
        return PluginType.Step;
    }

    @Override
    public String cancel() {
        return "/uii" + returnPath;
    }

    @Override
    public String finish() {
        return "/uii" + returnPath;
    }

    @Override
    public int getInterfaceVersion() {
        return 0;
    }

    @Override
    public HashMap<String, StepReturnValue> validate() {
        return null;
    }

    @Override
    public boolean execute() {
        PluginReturnValue ret = run();
        return ret != PluginReturnValue.ERROR;
    }

    @Override
    public PluginReturnValue run() {
        boolean successful = true;
        // your logic goes here

        // check ocr folder
        successful = successful && checkOcrFolder();

        // get text value from ocr folder
        successful = successful && prepareTextValue();

        // write the combined text value to the mets file
        successful = successful && saveTextValueToMets();

        log.info("OcrToMetadata step plugin executed");
        return successful ? PluginReturnValue.FINISH : PluginReturnValue.ERROR;
    }

    private boolean checkOcrFolder() {
        try {
            String ocrDir = process.getOcrDirectory();
            boolean success = checkExistenceOfDirectory(ocrDir);

            String ocrTextDir = process.getOcrTxtDirectory();
            ocrTextFolderAvailable = success && checkExistenceOfDirectory(ocrTextDir);

            // check alto directory only when there is no text folder available
            success = ocrTextFolderAvailable || checkExistenceOfDirectory(process.getOcrAltoDirectory());

            log.debug("ocrDir = " + ocrDir);
            log.debug("ocrTextDir = " + ocrTextDir);
            log.debug("ocrTextFolderAvailable = " + ocrTextFolderAvailable);
            log.debug("ocr text / alto folder available = " + success);

            return success;
        } catch (SwapException | IOException e) {
            String message = "failed to get the ocr folder";
            logBoth(process.getId(), LogType.ERROR, message);
            return false;
        }
    }

    private boolean checkExistenceOfDirectory(String directory) {
        Path path = Path.of(directory);
        return storageProvider.isFileExists(path) && storageProvider.isDirectory(path);
    }

    private boolean prepareTextValue() {
        try {
            String directory = ocrTextFolderAvailable ? process.getOcrTxtDirectory() : process.getOcrAltoDirectory();
            textValue = getTextValueFromFolder(directory);
            log.debug("textValue = " + textValue);

            return textValue != null;

        } catch (SwapException | IOException e) {
            String message = "failed to retrieve the directory";
            logBoth(process.getId(), LogType.ERROR, message);
            return false;
        }
    }

    private String getTextValueFromFolder(String directory) {
        StringBuilder sb = new StringBuilder();
        List<Path> files = storageProvider.listFiles(directory);
        for (Path file : files) {
            log.debug("file = " + file);
            String content = ocrTextFolderAvailable ? readContentFromTextFile(file) : readContentFromAltoFile(file);
            sb.append(content);
        }

        return sb.length() > 0 ? sb.toString() : null;
    }

    private String readContentFromTextFile(Path textFile) {
        try (InputStream input = storageProvider.newInputStream(textFile);
                ByteArrayOutputStream result = new ByteArrayOutputStream()) {

            byte[] buffer = new byte[1024];
            int length;
            while ((length = input.read(buffer)) != -1) {
                result.write(buffer, 0, length);
            }

            return result.toString(StandardCharsets.UTF_8);

        } catch (IOException e) {
            String message = "IOException caught while trying to read the content from the text file: " + textFile;
            logBoth(process.getId(), LogType.ERROR, message);
            return "";
        }
    }

    private String readContentFromAltoFile(Path altoFile) {

        return "";
    }

    private boolean saveTextValueToMets() {

        return true;
    }

    /**
     * print logs to terminal and journal
     * 
     * @param processId id of the Goobi process
     * @param logType type of the log
     * @param message message to be shown to both terminal and journal
     */
    private void logBoth(int processId, LogType logType, String message) {
        String logMessage = "OcrToMetadata Step Plugin: " + message;
        switch (logType) {
            case ERROR:
                log.error(logMessage);
                break;
            case DEBUG:
                log.debug(logMessage);
                break;
            case WARN:
                log.warn(logMessage);
                break;
            default: // INFO
                log.info(logMessage);
                break;
        }
        if (processId > 0) {
            Helper.addMessageToProcessJournal(processId, logType, logMessage);
        }
    }

}
