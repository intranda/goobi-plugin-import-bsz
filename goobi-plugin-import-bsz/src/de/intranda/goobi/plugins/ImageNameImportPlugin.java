package de.intranda.goobi.plugins;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import org.apache.commons.io.filefilter.FileFileFilter;
import org.apache.log4j.Logger;
import org.goobi.production.cli.helper.StringPair;
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
import de.sub.goobi.forms.MassImportForm;
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
    private String ppnAnalog = "";
    private String ppnDigital = "";
    private String ats = "";
    private static MetadataType CATALOGIDDIGITAL_TYPE;
    private static MetadataType CATALOGIDSOURCE_TYPE;
    private static MetadataType SHELFMARK_TYPE;
    private static MetadataType COLLECTION_TYPE;
    private MassImportForm form;

    // TODO anpasen
    private static final String IMAGE_FOLDER_EXTENSION = "_tif";
    private static final File ROOT_FOLDER = new File("/opt/digiverso/BSZ/");

    private static final String REGEX =
            "\\w{9}\\-S\\d{4}\\-(volume|text|image|cover_front|cover_back|title_page|contents|preface|index|additional)\\-\\w\\d{4}.tif";

    private List<StringPair> identifierList = new ArrayList<>(80);

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
                    } else if (filename.endsWith(".txt")) {
                    } else {
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

    public String getDescription() {
        return PLUGIN_NAME;
    }

    @Override
    public void setPrefs(Prefs prefs) {
        this.prefs = prefs;
        CATALOGIDDIGITAL_TYPE = prefs.getMetadataTypeByName("CatalogIDDigital");
        CATALOGIDSOURCE_TYPE = prefs.getMetadataTypeByName("CatalogIDSource");
        SHELFMARK_TYPE = prefs.getMetadataTypeByName("shelfmarksource");
        COLLECTION_TYPE = prefs.getMetadataTypeByName("singleDigCollection");
    }

    @Override
    public void setData(Record r) {
        this.ppnAnalog = r.getData();

    }

    @Override
    public Fileformat convertData() throws ImportPluginException {
        try {
            ConfigOpacCatalogue coc = new ConfigOpac().getCatalogueByName("SWB");
            IOpacPlugin myImportOpac = (IOpacPlugin) PluginLoader.getPluginByTitle(PluginType.Opac, coc.getOpacType());
            Fileformat myRdf = myImportOpac.search("12", ppnDigital, coc, prefs);
            if (myRdf != null) {
                try {
                    ats = myImportOpac.createAtstsl(myRdf.getDigitalDocument().getLogicalDocStruct().getAllMetadataByType(prefs.getMetadataTypeByName(
                            "TitleDocMain")).get(0).getValue(), null).toLowerCase();

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

        // add analog, add prefix bsz

        if (identifierSourceList.isEmpty()) {
            try {
                Metadata md = new Metadata(CATALOGIDSOURCE_TYPE);
                md.setValue("bsz" + ppnAnalog);
                ds.addMetadata(md);
            } catch (MetadataTypeNotAllowedException e) {
                logger.error(e);
            }
        } else {
            for (Metadata md : identifierSourceList) {
                if (!md.getValue().startsWith("bsz")) {
                    md.setValue("bsz" + md.getValue());
                }
            }
        }

        for (Metadata md : identifierDigitalList) {
            // add prefix bsz
            md.setValue("bsz" + md.getValue());
        }

        //        // PPN digital is missing
        //        if (identifierDigitalList.isEmpty()) {
        //            // check for PPN analog 
        //            if (!identifierSourceList.isEmpty()) {
        //                try {
        //
        //                    String identifierSourceValue = identifierSourceList.get(0).getValue();
        //                    Metadata identifierDigital = new Metadata(CATALOGIDDIGITAL_TYPE);
        //
        //                    if (identifierSourceValue.equals("011770007")) {
        //                        identifierDigital.setValue("bsz430939787");
        //                    } else if (identifierSourceValue.equals("049472445")) {
        //                        identifierDigital.setValue("bsz43133286X");
        //                    } else if (identifierSourceValue.equals("035072075")) {
        //                        identifierDigital.setValue("bsz431333815");
        //                    } else if (identifierSourceValue.equals("052645894")) {
        //                        identifierDigital.setValue("bsz43215101X");
        //                    } else if (identifierSourceValue.equals("051225395")) {
        //                        identifierDigital.setValue("bsz432167501");
        //                    }
        //                    ppnDigital = identifierDigital.getValue();
        //                    ds.addMetadata(identifierDigital);
        //                } catch (MetadataTypeNotAllowedException e) {
        //                    logger.error(e);
        //                }
        //            }
        //        }

        // alle Signaturen entfernen
        List<? extends Metadata> shelfmarkList = ds.getAllMetadataByType(SHELFMARK_TYPE);
        if (!shelfmarkList.isEmpty()) {
            for (Metadata md : shelfmarkList) {
                ds.removeMetadata(md);
            }
        }

        try {
            Metadata col;
            col = new Metadata(COLLECTION_TYPE);
            col.setValue("Universität Konstanz");
            ds.addMetadata(col);
        } catch (MetadataTypeNotAllowedException e) {
            logger.error(e);
        }
    }

    @Override
    public String getImportFolder() {
        return importFolder;
    }

    @Override
    public String getProcessTitle() {
        return ats + "_" + ppnDigital;
    }

    @Override
    public List<ImportObject> generateFiles(List<Record> records) {
        generateIdentifierList();

        // 1. Validierung
        // 2. convert
        // 3. Bilder kopieren

        List<ImportObject> answer = new ArrayList<ImportObject>();
        for (Record record : records) {
            form.addProcessToProgressBar();
            ImportObject io = new ImportObject();
            ppnAnalog = record.getId();
            ppnDigital = getDigitalPPN();
            if (ppnDigital.isEmpty()) {
                continue;
            }
            String folder = ROOT_FOLDER.getAbsolutePath() + File.separator + ppnAnalog;
            List<String> validatedData = validate(folder);
            if (!validatedData.isEmpty()) {
                logger.error(folder + " is not valid");
                for (String value : validatedData) {
                    logger.error(value);
                }
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
                            pathimagefiles.setValue(ppnAnalog);
                            physical.addMetadata(pathimagefiles);

                            dd.setPhysicalDocStruct(physical);
                        } catch (PreferencesException | TypeNotAllowedForParentException | MetadataTypeNotAllowedException e1) {
                            logger.error(e1);
                        }

                        try {
                            MetsMods mm = new MetsMods(this.prefs);
                            mm.setDigitalDocument(fileformat.getDigitalDocument());
                            String fileName = getImportFolder() + ppnAnalog + ".xml";
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

    private String getDigitalPPN() {
        for (StringPair sp : identifierList) {
            if (sp.getTwo().equals(ppnAnalog)) {
                return sp.getOne();
            }
        }

        return "";
    }

    private void generateIdentifierList() {
        {
            StringPair sp = new StringPair("435423762", "00030879X");
            identifierList.add(sp);
        }
        {
            StringPair sp = new StringPair("443435375", "000035556");
            identifierList.add(sp);
        }
        {
            StringPair sp = new StringPair("443437165", "00112045X");
            identifierList.add(sp);
        }
        {
            StringPair sp = new StringPair("443416559", "000158437");
            identifierList.add(sp);
        }
        {
            StringPair sp = new StringPair("44337600X", "00166395X");
            identifierList.add(sp);
        }
        {
            StringPair sp = new StringPair("443415927", "000181498");
            identifierList.add(sp);
        }
        {
            StringPair sp = new StringPair("435424327", "000196606");
            identifierList.add(sp);
        }
        {
            StringPair sp = new StringPair("443400903", "000220949");
            identifierList.add(sp);
        }
        {
            StringPair sp = new StringPair("443401101", "000220957");
            identifierList.add(sp);
        }
        {
            StringPair sp = new StringPair("443374953", "000301914");
            identifierList.add(sp);
        }
        {
            StringPair sp = new StringPair("435163558", "00426990X");
            identifierList.add(sp);
        }
        {
            StringPair sp = new StringPair("435423223", "000438855");
            identifierList.add(sp);
        }
        {
            StringPair sp = new StringPair("443375321", "00509139X");
            identifierList.add(sp);
        }
        {
            StringPair sp = new StringPair("435162101", "000546739");
            identifierList.add(sp);
        }
        {
            StringPair sp = new StringPair("435379577", "00659056X");
            identifierList.add(sp);
        }
        {
            StringPair sp = new StringPair("443208107", "000674710");
            identifierList.add(sp);
        }
        {
            StringPair sp = new StringPair("467811105", "00714105X");
            identifierList.add(sp);
        }
        {
            StringPair sp = new StringPair("468062203", "00714119X");
            identifierList.add(sp);
        }
        {
            StringPair sp = new StringPair("468063412", "00714122X");
            identifierList.add(sp);
        }
        {
            StringPair sp = new StringPair("443401349", "000779962");
            identifierList.add(sp);
        }
        {
            StringPair sp = new StringPair("443401713", "000779970");
            identifierList.add(sp);
        }
        {
            StringPair sp = new StringPair("443402043", "000779989");
            identifierList.add(sp);
        }
        {
            StringPair sp = new StringPair("443409153", "000876828");
            identifierList.add(sp);
        }
        {
            StringPair sp = new StringPair("443409463", "000876836");
            identifierList.add(sp);
        }
        {
            StringPair sp = new StringPair("443409633", "000876844");
            identifierList.add(sp);
        }
        {
            StringPair sp = new StringPair("443409862", "000876852");
            identifierList.add(sp);
        }
        {
            StringPair sp = new StringPair("443409994", "000876860");
            identifierList.add(sp);
        }
        {
            StringPair sp = new StringPair("443410178", "000876879");
            identifierList.add(sp);
        }
        {
            StringPair sp = new StringPair("435510207", "001021362");
            identifierList.add(sp);
        }
        {
            StringPair sp = new StringPair("443436924", "001120441");
            identifierList.add(sp);
        }
        {
            StringPair sp = new StringPair("443437335", "001120468");
            identifierList.add(sp);
        }
        {
            StringPair sp = new StringPair("443436746", "001120476");
            identifierList.add(sp);
        }
        {
            StringPair sp = new StringPair("435510894", "001150979");
            identifierList.add(sp);
        }
        {
            StringPair sp = new StringPair("435380834", "001400169");
            identifierList.add(sp);
        }
        {
            StringPair sp = new StringPair("443396124", "001782568");
            identifierList.add(sp);
        }
        {
            StringPair sp = new StringPair("435201492", "001815385");
            identifierList.add(sp);
        }
        {
            StringPair sp = new StringPair("443394377", "001838806");
            identifierList.add(sp);
        }
        {
            StringPair sp = new StringPair("443394725", "001838814");
            identifierList.add(sp);
        }
        {
            StringPair sp = new StringPair("435378759", "002048752");
            identifierList.add(sp);
        }
        {
            StringPair sp = new StringPair("443416125", "02126175X");
            identifierList.add(sp);
        }
        {
            StringPair sp = new StringPair("435160974", "002200422");
            identifierList.add(sp);
        }
        {
            StringPair sp = new StringPair("443435162", "002235730");
            identifierList.add(sp);
        }
        {
            StringPair sp = new StringPair("443395209", "002239434");
            identifierList.add(sp);
        }
        {
            StringPair sp = new StringPair("443395632", "002239442");
            identifierList.add(sp);
        }
        {
            StringPair sp = new StringPair("44339587X", "002239450");
            identifierList.add(sp);
        }
        {
            StringPair sp = new StringPair("443370060", "002240963");
            identifierList.add(sp);
        }
        {
            StringPair sp = new StringPair("434788260", "002268086");
            identifierList.add(sp);
        }
        {
            StringPair sp = new StringPair("443375607", "003128148");
            identifierList.add(sp);
        }
        {
            StringPair sp = new StringPair("435379968", "003245101");
            identifierList.add(sp);
        }
        {
            StringPair sp = new StringPair("435505025", "003274527");
            identifierList.add(sp);
        }
        {
            StringPair sp = new StringPair("435380362", "003776395");
            identifierList.add(sp);
        }
        {
            StringPair sp = new StringPair("443372012", "004168666");
            identifierList.add(sp);
        }
        {
            StringPair sp = new StringPair("443370818", "004177339");
            identifierList.add(sp);
        }
        {
            StringPair sp = new StringPair("443211221", "004545982");
            identifierList.add(sp);
        }
        {
            StringPair sp = new StringPair("443435022", "004661877");
            identifierList.add(sp);
        }
        {
            StringPair sp = new StringPair("435212486", "006557511");
            identifierList.add(sp);
        }
        {
            StringPair sp = new StringPair("435202952", "006633064");
            identifierList.add(sp);
        }
        {
            StringPair sp = new StringPair("435206125", "006757936");
            identifierList.add(sp);
        }
        {
            StringPair sp = new StringPair("435206869", "006818803");
            identifierList.add(sp);
        }
        {
            StringPair sp = new StringPair("443436304", "007083319");
            identifierList.add(sp);
        }
        {
            StringPair sp = new StringPair("443435979", "007083327");
            identifierList.add(sp);
        }
        {
            StringPair sp = new StringPair("443436142", "007083335");
            identifierList.add(sp);
        }
        {
            StringPair sp = new StringPair("46288810X", "007140894");
            identifierList.add(sp);
        }
        {
            StringPair sp = new StringPair("462889246", "007140908");
            identifierList.add(sp);
        }
        {
            StringPair sp = new StringPair("462906124", "007140916");
            identifierList.add(sp);
        }
        {
            StringPair sp = new StringPair("462915409", "007140924");
            identifierList.add(sp);
        }
        {
            StringPair sp = new StringPair("462915832", "007140932");
            identifierList.add(sp);
        }
        {
            StringPair sp = new StringPair("462916367", "007140940");
            identifierList.add(sp);
        }
        {
            StringPair sp = new StringPair("467757356", "007140959");
            identifierList.add(sp);
        }
        {
            StringPair sp = new StringPair("467758182", "007140967");
            identifierList.add(sp);
        }
        {
            StringPair sp = new StringPair("467758492", "007140975");
            identifierList.add(sp);
        }
        {
            StringPair sp = new StringPair("46775859X", "007140983");
            identifierList.add(sp);
        }
        {
            StringPair sp = new StringPair("467768692", "007140991");
            identifierList.add(sp);
        }
        {
            StringPair sp = new StringPair("467768919", "007141009");
            identifierList.add(sp);
        }
        {
            StringPair sp = new StringPair("467769575", "007141017");
            identifierList.add(sp);
        }
        {
            StringPair sp = new StringPair("467769826", "007141025");
            identifierList.add(sp);
        }
        {
            StringPair sp = new StringPair("467770166", "007141033");
            identifierList.add(sp);
        }
        {
            StringPair sp = new StringPair("467770255", "007141041");
            identifierList.add(sp);
        }
        {
            StringPair sp = new StringPair("467811555", "007141068");
            identifierList.add(sp);
        }
        {
            StringPair sp = new StringPair("468002766", "007141076");
            identifierList.add(sp);
        }
        {
            StringPair sp = new StringPair("46800307X", "007141084");
            identifierList.add(sp);
        }
        {
            StringPair sp = new StringPair("468003282", "007141092");
            identifierList.add(sp);
        }
        {
            StringPair sp = new StringPair("46800355X", "007141106");
            identifierList.add(sp);
        }
        {
            StringPair sp = new StringPair("46800372X", "007141114");
            identifierList.add(sp);
        }
        {
            StringPair sp = new StringPair("007141122", "007141122");
            identifierList.add(sp);
        }
        {
            StringPair sp = new StringPair("468010947", "007141130");
            identifierList.add(sp);
        }
        {
            StringPair sp = new StringPair("468011277", "007141149");
            identifierList.add(sp);
        }
        {
            StringPair sp = new StringPair("468011420", "007141157");
            identifierList.add(sp);
        }
        {
            StringPair sp = new StringPair("468011730", "007141165");
            identifierList.add(sp);
        }
        {
            StringPair sp = new StringPair("468011951", "007141173");
            identifierList.add(sp);
        }
        {
            StringPair sp = new StringPair("468012206", "007141181");
            identifierList.add(sp);
        }
        {
            StringPair sp = new StringPair("468062548", "007141203");
            identifierList.add(sp);
        }
        {
            StringPair sp = new StringPair("468063080", "007141211");
            identifierList.add(sp);
        }
        {
            StringPair sp = new StringPair("435424114", "007249454");
            identifierList.add(sp);
        }
        {
            StringPair sp = new StringPair("44321087X", "007587155");
            identifierList.add(sp);
        }
        {
            StringPair sp = new StringPair("435209175", "007592825");
            identifierList.add(sp);
        }
        {
            StringPair sp = new StringPair("435510541", "007607024");
            identifierList.add(sp);
        }
        {
            StringPair sp = new StringPair("443375844", "008027013");
            identifierList.add(sp);
        }
        {
            StringPair sp = new StringPair("443434743", "008237360");
            identifierList.add(sp);
        }
        {
            StringPair sp = new StringPair("443434883", "008237379");
            identifierList.add(sp);
        }
        {
            StringPair sp = new StringPair("443210225", "008978921");
            identifierList.add(sp);
        }
        {
            StringPair sp = new StringPair("435423460", "008990956");
            identifierList.add(sp);
        }
        {
            StringPair sp = new StringPair("435202235", "009814116");
            identifierList.add(sp);
        }
        {
            StringPair sp = new StringPair("430939787", "011770007");
            identifierList.add(sp);
        }
        {
            StringPair sp = new StringPair("443371393", "013657755");
            identifierList.add(sp);
        }
        {
            StringPair sp = new StringPair("443371555", "017749778");
            identifierList.add(sp);
        }
        {
            StringPair sp = new StringPair("443436541", "025519956");
            identifierList.add(sp);
        }
        {
            StringPair sp = new StringPair("443436657", "025520016");
            identifierList.add(sp);
        }
        {
            StringPair sp = new StringPair("431333815", "035072075");
            identifierList.add(sp);
        }
        {
            StringPair sp = new StringPair("435212974", "035977396");
            identifierList.add(sp);
        }
        {
            StringPair sp = new StringPair("467811385", "045086621");
            identifierList.add(sp);
        }
        {
            StringPair sp = new StringPair("43133286X", "049472445");
            identifierList.add(sp);
        }
        {
            StringPair sp = new StringPair("432167501", "051225395");
            identifierList.add(sp);
        }
        {
            StringPair sp = new StringPair("43215101X", "052645894");
            identifierList.add(sp);
        }
        {
            StringPair sp = new StringPair("435510703", "053223721");
            identifierList.add(sp);
        }
        {
            StringPair sp = new StringPair("443372187", "053411358");
            identifierList.add(sp);
        }
        {
            StringPair sp = new StringPair("443211469", "055416942");
            identifierList.add(sp);
        }
        {
            StringPair sp = new StringPair("443209693", "056668023");
            identifierList.add(sp);
        }
        {
            StringPair sp = new StringPair("44337175X", "072756713");
            identifierList.add(sp);
        }
        {
            StringPair sp = new StringPair("468012397", "085162019");
            identifierList.add(sp);
        }
        {
            StringPair sp = new StringPair("468063765", "085263826");
            identifierList.add(sp);
        }
        {
            StringPair sp = new StringPair("468064001", "085263877");
            identifierList.add(sp);
        }
        {
            StringPair sp = new StringPair("468064303", "085263931");
            identifierList.add(sp);
        }
        {
            StringPair sp = new StringPair("468064494", "085263990");
            identifierList.add(sp);
        }
        {
            StringPair sp = new StringPair("468254404", "085264091");
            identifierList.add(sp);
        }
        {
            StringPair sp = new StringPair("468254536", "085264164");
            identifierList.add(sp);
        }
        {
            StringPair sp = new StringPair("468254919", "085264202");
            identifierList.add(sp);
        }
        {
            StringPair sp = new StringPair("46825501X", "085264261");
            identifierList.add(sp);
        }
        {
            StringPair sp = new StringPair("468256245", "085264350");
            identifierList.add(sp);
        }
        {
            StringPair sp = new StringPair("468256482", "085264385");
            identifierList.add(sp);
        }
        {
            StringPair sp = new StringPair("468256687", "085264423");
            identifierList.add(sp);
        }
        {
            StringPair sp = new StringPair("468256903", "085264490");
            identifierList.add(sp);
        }
        {
            StringPair sp = new StringPair("44343588X", "089633857");
            identifierList.add(sp);
        }
    }

    private void moveData() {

        File imageFolder = new File(ROOT_FOLDER, ppnAnalog);

        File destination = new File(getImportFolder() + File.separator + ppnAnalog + File.separator + "images" + File.separator + getProcessTitle()
                + IMAGE_FOLDER_EXTENSION);
        destination.mkdirs();
        try {
            logger.info("copy data from " + imageFolder.getAbsolutePath() + " to " + destination.getAbsolutePath());
            // TODO
            org.apache.commons.io.FileUtils.copyDirectory(imageFolder, destination);
        } catch (Exception e) {
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

    @Override
    public void setForm(MassImportForm form) {
        this.form = form;

    }
}
