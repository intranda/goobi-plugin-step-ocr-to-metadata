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
package de.intranda.goobi.plugins;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
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
import org.jdom2.JDOMException;

import de.intranda.digiverso.ocr.alto.model.structureclasses.Page;
import de.intranda.digiverso.ocr.alto.model.structureclasses.logical.AltoDocument;
import de.sub.goobi.config.ConfigPlugins;
import de.sub.goobi.helper.Helper;
import de.sub.goobi.helper.StorageProvider;
import de.sub.goobi.helper.StorageProviderInterface;
import de.sub.goobi.helper.exceptions.SwapException;
import lombok.Getter;
import lombok.extern.log4j.Log4j2;
import net.xeoh.plugins.base.annotations.PluginImplementation;
import ugh.dl.DocStruct;
import ugh.dl.Fileformat;
import ugh.dl.Metadata;
import ugh.dl.MetadataType;
import ugh.dl.Prefs;
import ugh.exceptions.DocStructHasNoTypeException;
import ugh.exceptions.MetadataTypeNotAllowedException;
import ugh.exceptions.PreferencesException;
import ugh.exceptions.ReadException;
import ugh.exceptions.WriteException;

@PluginImplementation
@Log4j2
public class OcrToMetadataStepPlugin implements IStepPluginVersion2 {
    private static StorageProviderInterface storageProvider = StorageProvider.getInstance();

    @Getter
    private String title = "intranda_step_ocr_to_metadata";
    @Getter
    private Step step;

    private Process process;

    private String returnPath;
    @Getter
    private String metadataField;
    // text value that will be read from the ocr folder and saved to the mets file
    private String textValue = null;
    // true if ocr text folder is available, false otherwise
    private boolean ocrTextFolderAvailable;
    // the old Metadata object of the same type specified by metadataField, which will be replaced in its existence
    private Metadata oldMetadata = null;

    private transient Fileformat fileformat;
    private DocStruct logical;

    @Override
    public void initialize(Step step, String returnPath) {
        this.returnPath = returnPath;
        this.step = step;

        process = this.step.getProzess();

        // read parameters from correct block in configuration file
        SubnodeConfiguration myconfig = ConfigPlugins.getProjectAndStepConfig(title, step);
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
        // won't be used
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
        // check ocr folder
        boolean successful = checkOcrFolder();
        
        // initialize the fields fileformat and logical
        successful = successful && initializeLogicalDocStruct();

        // validate the field metadataField, check its existence or addability
        successful = successful && validateMetadataField();

        // initialize the field textValue with contents from ocr folder
        successful = successful && prepareTextValue();

        // save the textValue to the mets file
        successful = successful && saveTextValueToMets();

        log.info("OcrToMetadata step plugin executed");
        return successful ? PluginReturnValue.FINISH : PluginReturnValue.ERROR;
    }

    /**
     * initialize the fields fileformat and logical
     * 
     * @return true if both fields are initialized successfully, false if any exception should be caught
     */
    private boolean initializeLogicalDocStruct() {
        try {
            fileformat = process.readMetadataFile();
            logical = fileformat.getDigitalDocument().getLogicalDocStruct();
            return true;

        } catch (ReadException | IOException | SwapException | PreferencesException e) {
            String message = "failed to initialize the logical DocStruct";
            logBoth(process.getId(), LogType.ERROR, message);
            return false;
        }
    }

    /**
     * validate the MetadataType specified by the field metadataField
     * 
     * @return true if metadataField specifies a valid MetadataType for the logical DocStruct, false otherwise
     */
    private boolean validateMetadataField() {
        // check existence first
        boolean isValid = checkExistenceOfMetadata(logical, metadataField);
        // check addability of this Metadata only if it does not exist yet
        isValid = isValid || checkAddabilityOfMetadata(logical, metadataField);

        return isValid;
    }

    /**
     * check the existence of a Metadata of the given type in the given DocStruct
     * 
     * @param ds DocStruct
     * @param metadataType type of the Metadata that is targeted
     * @return true if such a Metadata exists, false otherwise
     */
    private boolean checkExistenceOfMetadata(DocStruct ds, String metadataType) {
        if (ds.getAllMetadata() != null) {
            for (Metadata md : ds.getAllMetadata()) {
                if (md.getType().getName().equals(metadataType)) {
                    oldMetadata = md;
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * check the addability of a Metadata of the given type into the given DocStruct
     * 
     * @param ds DocStruct
     * @param metadataType type of the Metadata that is planned to be added
     * @return true if the input MetadataType is allowed to be added, false otherwise
     */
    private boolean checkAddabilityOfMetadata(DocStruct ds, String metadataType) {
        boolean includeHiddenMetadata = true;
        List<MetadataType> allowedMetadataTypes = ds.getAddableMetadataTypes(includeHiddenMetadata);
        for (MetadataType mdType : allowedMetadataTypes) {
            String mdTypeName = mdType.getName();
            if (metadataType.equals(mdTypeName)) {
                return true;
            }
        }

        return false;
    }

    /**
     * check the existence of needed ocr folders
     * 
     * @return true if the text directory or the alto directory exists, false otherwise
     */
    private boolean checkOcrFolder() {
        try {
            String ocrTextDir = process.getOcrTxtDirectory();
            ocrTextFolderAvailable = checkExistenceOfDirectory(ocrTextDir);

            // check alto directory only when there is no text folder available
            return ocrTextFolderAvailable || checkExistenceOfDirectory(process.getOcrAltoDirectory());

        } catch (SwapException | IOException e) {
            String message = "failed to get the ocr folder";
            logBoth(process.getId(), LogType.ERROR, message);
            return false;
        }
    }

    /**
     * check the existence of a directory
     * 
     * @param directory absolute path as a string of a directory
     * @return true if such a path exists as a directory, false otherwise
     */
    private boolean checkExistenceOfDirectory(String directory) {
        Path path = Path.of(directory);
        return storageProvider.isFileExists(path) && storageProvider.isDirectory(path);
    }

    /**
     * prepare the value for the field textValue
     * 
     * @return true if textValue is successfully initialized, false otherwise
     */
    private boolean prepareTextValue() {
        try {
            String directory = ocrTextFolderAvailable ? process.getOcrTxtDirectory() : process.getOcrAltoDirectory();
            textValue = getTextValueFromFolder(directory);
            return textValue != null;

        } catch (SwapException | IOException e) {
            String message = "failed to retrieve the directory";
            logBoth(process.getId(), LogType.ERROR, message);
            return false;
        }
    }

    /**
     * get contents from the given ocr folder
     * 
     * @param directory absolute path to the specified ocr folder
     * @return contents of files from the ocr folder combined if it is not empty, or null otherwise
     */
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

    /**
     * read the contents from a text file
     * 
     * @param textFile absolute path of the text file
     * @return contents of the text file as a string, or an empty string if any IOException should occur
     */
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

    /**
     * read the contents from an alto file
     * 
     * @param altoFile absolute path of the alto file
     * @return contents of the alto file as a string, or an empty string if any IOException or JDOMException should occur
     */
    private String readContentFromAltoFile(Path altoFile) {
        AltoDocument altoDoc = null;
        try {
            altoDoc = AltoDocument.getDocumentFromFile(altoFile.toFile());
        } catch (IOException | JDOMException e) {
            String message = "failed to read the content from the alto file: " + altoFile;
            logBoth(process.getId(), LogType.ERROR, message);
            return "";
        }

        StringBuilder sb = new StringBuilder();

        List<Page> pages = altoDoc.getAllPagesAsList();
        for (Page page : pages) {
            String pageContent = page.getContent();
            sb.append(pageContent);
            sb.append("\n");
        }

        int length = sb.length();
        if (length > 0) {
            // delete the last \n
            sb.deleteCharAt(length - 1);
        }

        return sb.toString();
    }

    /**
     * save the value of the field textValue to the mets file
     * 
     * @return true if the mets file is successfully updated, false is any exception should occur
     */
    private boolean saveTextValueToMets() {
        try {
            Metadata newMetadata = prepareNewMetadata(metadataField, textValue);

            if (oldMetadata != null) {
                log.debug("replacing the old Metadata...");
                logical.changeMetadata(oldMetadata, newMetadata);
            } else {
                log.debug("adding new Metadata...");
                logical.addMetadata(newMetadata);
            }

            process.writeMetadataFile(fileformat);
            return true;

        } catch (MetadataTypeNotAllowedException e) {
            String message = "MetadataType not allowed";
            logBoth(process.getId(), LogType.ERROR, message);
            return false;

        } catch (IOException | SwapException | PreferencesException | DocStructHasNoTypeException | WriteException e) {
            String message = "failed to save the text value into the mets file";
            logBoth(process.getId(), LogType.ERROR, message);
            return false;
        }
    }

    /**
     * prepare a new Metadata object based on the input type and value
     * 
     * @param metadataTypeName type of the new Metadata object
     * @param value value of the new Metadata object
     * @return a Metadata object of the given type and initialized with the given value
     * @throws MetadataTypeNotAllowedException
     */
    private Metadata prepareNewMetadata(String metadataTypeName, String value) throws MetadataTypeNotAllowedException {
        log.debug("creating new Metadata of type: " + metadataTypeName);
        Prefs prefs = process.getRegelsatz().getPreferences();
        Metadata md = new Metadata(prefs.getMetadataTypeByName(metadataTypeName));
        md.setValue(value);
        return md;
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
