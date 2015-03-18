package de.intranda.goobi.plugins;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import org.apache.commons.io.filefilter.FileFileFilter;
import org.apache.log4j.Logger;
import org.goobi.production.enums.ImportReturnValue;
import org.goobi.production.enums.ImportType;
import org.goobi.production.enums.PluginType;
import org.goobi.production.importer.DocstructElement;
import org.goobi.production.importer.ImportObject;
import org.goobi.production.importer.Record;
import org.goobi.production.plugin.PluginLoader;
import org.goobi.production.plugin.interfaces.IImportPlugin;
import org.goobi.production.plugin.interfaces.IOpacPlugin;
import org.goobi.production.plugin.interfaces.IPlugin;
import org.goobi.production.properties.ImportProperty;

import ugh.dl.DigitalDocument;
import ugh.dl.DocStruct;
import ugh.dl.DocStructType;
import ugh.dl.Fileformat;
import ugh.dl.Metadata;
import ugh.dl.MetadataType;
import ugh.dl.Prefs;
import ugh.exceptions.MetadataTypeNotAllowedException;
import ugh.exceptions.PreferencesException;
import ugh.exceptions.TypeNotAllowedForParentException;
import ugh.exceptions.WriteException;
import ugh.fileformats.mets.MetsMods;
import de.sub.goobi.helper.exceptions.ImportPluginException;
import de.unigoettingen.sub.search.opac.ConfigOpac;
import de.unigoettingen.sub.search.opac.ConfigOpacCatalogue;
import net.xeoh.plugins.base.annotations.PluginImplementation;

@PluginImplementation
public class ImageNameImportPlugin implements IImportPlugin, IPlugin {

    private static final Logger logger = Logger.getLogger(ImageNameImportPlugin.class);

    private static final String PLUGIN_NAME = "ImageNameImportPlugin";
   
    
    private Prefs prefs;
    private String importFolder = "";
    private String data = "";
    private String ats = "";
    private static MetadataType CATALOGIDDIGITAL_TYPE;
    private static MetadataType CATALOGIDSOURCE_TYPE;
    private static MetadataType SHELFMARK_TYPE;

    // TODO anpasen
    private static final String IMAGE_FOLDER_EXTENSION = "_media";
    private static final File ROOT_FOLDER = new File("/home/robert/Downloads/bsz/");


    private static final String REGEX =
            "\\w{9}\\-S\\d{4}\\-(volume|text|image|cover_front|cover_back|title_page|contents|preface|index|additional)\\-\\w\\d{4}.tif";

    private static List<String> validate(String folderPath) {
        List<String> answer = new ArrayList<String>();

        File folder = new File(folderPath);
        if (!folder.exists()) {
            answer.add("Folder " + folderPath + " does not exist");

        } else if (!folder.isDirectory()) {
            answer.add(folderPath + " is no directory.");
        } else {
            String[] files = folder.list(FileFileFilter.FILE);

            if (files == null || files.length == 0) {
                answer.add(folderPath + " is empty.");
            } else {
                List<String> filenames = Arrays.asList(files);
                Collections.sort(filenames);
                for (String filename : filenames) {
                    if (filename.endsWith("pdf")) {

                    } else if (filename.endsWith("xml")) {

                    } else if (filename.equals("Thumbs.db")) {
                        //                        logger.debug("Thumbs.db will be ignored");
                    }

                    else {
                        if (!filename.matches(REGEX)) {
                            answer.add(filename + " does not match naming conventions.");
                            //                        } else {
                            //                            logger.debug("match: " + filename);
                        }
                    }
                }
            }
        }
        return answer;
    }

    @Override
    public PluginType getType() {
        return PluginType.Import;
    }

    @Override
    public String getTitle() {
        return PLUGIN_NAME;
    }

    @Override
    public String getDescription() {
        return PLUGIN_NAME;
    }

    @Override
    public void setPrefs(Prefs prefs) {
        this.prefs = prefs;
        CATALOGIDDIGITAL_TYPE = prefs.getMetadataTypeByName("CatalogIDDigital");
        CATALOGIDSOURCE_TYPE = prefs.getMetadataTypeByName("CatalogIDSource");
        SHELFMARK_TYPE = prefs.getMetadataTypeByName("shelfmarksource");
    }

    @Override
    public void setData(Record r) {
        this.data = r.getData();

    }

    @Override
    public Fileformat convertData() throws ImportPluginException {
        try {
            ConfigOpacCatalogue coc = new ConfigOpac().getCatalogueByName("SWB");
            IOpacPlugin myImportOpac = (IOpacPlugin) PluginLoader.getPluginByTitle(PluginType.Opac, coc.getOpacType());
            Fileformat myRdf = myImportOpac.search("12", data, coc, prefs);
            if (myRdf != null) {
                try {
                    ats =
                            myImportOpac.createAtstsl(myRdf.getDigitalDocument().getLogicalDocStruct().getAllMetadataByType(
                                    prefs.getMetadataTypeByName("TitleDocMain")).get(0).getValue(), null).toLowerCase();

                } catch (Exception e) {
                    ats = "";
                }
            }

            DocStruct ds = myRdf.getDigitalDocument().getLogicalDocStruct();
            checkIdentifier(ds);

            if (ds.getType().isAnchor()) {
                DocStruct child = ds.getAllChildren().get(0);
                checkIdentifier(child);
            }
            return myRdf;
        } catch (IOException e1) {
            logger.error(e1);
        } catch (Exception e) {
            logger.error(e);
        }
        return null;
    }

    private void checkIdentifier(DocStruct ds) {

        List<? extends Metadata> identifierDigitalList = ds.getAllMetadataByType(CATALOGIDDIGITAL_TYPE);
        List<? extends Metadata> identifierSourceList = ds.getAllMetadataByType(CATALOGIDSOURCE_TYPE);

        // PPN digital is missing
        if (identifierDigitalList.isEmpty()) {
            // check for PPN analog 
            if (!identifierSourceList.isEmpty()) {
                try {
                    // TODO change this later
                    Metadata identifierDigital = new Metadata(CATALOGIDDIGITAL_TYPE);
                    identifierDigital.setValue(identifierSourceList.get(0).getValue());
                    ds.addMetadata(identifierDigital);
                } catch (MetadataTypeNotAllowedException e) {
                    logger.error(e);
                }
            }
        }

        // alle Signaturen entfernen
        List<? extends Metadata> shelfmarkList = ds.getAllMetadataByType(SHELFMARK_TYPE);
        if (!shelfmarkList.isEmpty()) {
            for (Metadata md : shelfmarkList) {
                ds.removeMetadata(md);
            }
        }

    }

    @Override
    public String getImportFolder() {
        return importFolder;
    }

    @Override
    public String getProcessTitle() {
        return ats + "_" + data;
    }

    @Override
    public List<ImportObject> generateFiles(List<Record> records) {
        // 1. Validierung
        // 2. convert
        // 3. Bilder kopieren

        List<ImportObject> answer = new ArrayList<ImportObject>();
        for (Record record : records) {
            ImportObject io = new ImportObject();
            data = record.getId();
            String folder = ROOT_FOLDER.getAbsolutePath() + File.separator + data;
            if (!validate(folder).isEmpty()) {
                logger.error(folder + " is not valid");
            } else {
                try {
                    Fileformat fileformat = convertData();

                    if (fileformat != null) {

                        DigitalDocument dd;
                        try {
                            dd = fileformat.getDigitalDocument();
                            DocStructType docstructBoundBook = prefs.getDocStrctTypeByName("BoundBook");
                            DocStruct physical = dd.createDocStruct(docstructBoundBook);

                            Metadata pathimagefiles = new Metadata(prefs.getMetadataTypeByName("pathimagefiles"));
                            pathimagefiles.setValue(data);
                            physical.addMetadata(pathimagefiles);

                            dd.setPhysicalDocStruct(physical);
                        } catch (PreferencesException | TypeNotAllowedForParentException | MetadataTypeNotAllowedException e1) {
                            logger.error(e1);
                        }

                        try {
                            MetsMods mm = new MetsMods(this.prefs);
                            mm.setDigitalDocument(fileformat.getDigitalDocument());
                            String fileName = getImportFolder() + data + ".xml";
                            logger.debug("Writing '" + fileName + "' into given folder...");
                            mm.write(fileName);
                            io.setMetsFilename(fileName);
                            io.setProcessTitle(getProcessTitle());
                            io.setImportReturnValue(ImportReturnValue.ExportFinished);
                            //                io.setProcessProperties(processProperties);
                            //                io.setTemplateProperties(templateProperties);
                            //                io.setWorkProperties(workProperties);

                            moveData();

                        } catch (PreferencesException e) {
                            logger.error(e.getMessage(), e);
                            io.setImportReturnValue(ImportReturnValue.InvalidData);
                        } catch (WriteException e) {
                            logger.error(e.getMessage(), e);
                            io.setImportReturnValue(ImportReturnValue.WriteError);
                        }
                    } else {
                        io.setImportReturnValue(ImportReturnValue.InvalidData);
                    }
                    answer.add(io);

                } catch (ImportPluginException e) {
                    logger.error(e);
                }

            }
        }
        return answer;
    }

    private void moveData() {

        File imageFolder = new File(ROOT_FOLDER, data);

        File destination =
                new File(getImportFolder() + File.separator + data + File.separator + "images" + File.separator + getProcessTitle()
                        + IMAGE_FOLDER_EXTENSION);
        destination.mkdirs();
        try {
            org.apache.commons.io.FileUtils.copyDirectory(imageFolder, destination);
        } catch (IOException e) {
            logger.error(e);
        }

    }

    @Override
    public void setImportFolder(String folder) {
        this.importFolder = folder;

    }

    @Override
    public List<Record> splitRecords(String records) {
        return null;
    }

    @Override
    public List<Record> generateRecordsFromFile() {
        return null;
    }

    @Override
    public List<Record> generateRecordsFromFilenames(List<String> filenames) {
        List<Record> records = new ArrayList<Record>();
        for (String filename : filenames) {
            Record rec = new Record();
            rec.setData(filename);
            rec.setId(filename);
            records.add(rec);
        }
        return records;
    }

    @Override
    public void setFile(File importFile) {

    }

    @Override
    public List<String> splitIds(String ids) {
        return null;
    }

    @Override
    public List<ImportType> getImportTypes() {
        List<ImportType> typeList = new ArrayList<ImportType>();
        typeList.add(ImportType.FOLDER);
        return typeList;
    }

    @Override
    public List<ImportProperty> getProperties() {
        return null;
    }

    @Override
    public List<String> getAllFilenames() {
        List<String> folderNames = new ArrayList<String>();
        String[] files = ROOT_FOLDER.list();
        if (files != null && files.length > 0) {
            folderNames = Arrays.asList(files);
            Collections.sort(folderNames);
        }
        return folderNames;
    }

    @Override
    public void deleteFiles(List<String> selectedFilenames) {
        for (String filename : selectedFilenames) {
            File f = new File(ROOT_FOLDER, filename);
            // TODO delete
            logger.debug("Delete folder " + f.getAbsolutePath());
        }
    }

    @Override
    public List<? extends DocstructElement> getCurrentDocStructs() {
        return null;
    }

    @Override
    public String deleteDocstruct() {
        return null;
    }

    @Override
    public String addDocstruct() {
        return null;
    }

    @Override
    public List<String> getPossibleDocstructs() {
        return null;
    }

    @Override
    public DocstructElement getDocstruct() {
        return null;
    }

    @Override
    public void setDocstruct(DocstructElement dse) {
    }
    //  public static void main(String[] args) {
    //
    //      String path = "/home/robert/Downloads/bsz/007140975";
    //
    //      List<String> values = validate(path);
    //      if (!values.isEmpty()) {
    //          for (String message : values) {
    //              logger.error(message);
    //          }
    //      } else {
    //          logger.debug("Folder " + path + " is valid.");
    //      }
    //      ImageNameImportPlugin plugin = new ImageNameImportPlugin();
    //      for (String filename : plugin.getAllFilenames()) {
    //          System.out.println(filename);
    //      }
    //
    //  }
}
