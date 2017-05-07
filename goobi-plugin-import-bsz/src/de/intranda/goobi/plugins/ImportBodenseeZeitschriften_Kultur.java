package de.intranda.goobi.plugins;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;

import org.apache.commons.io.FileUtils;
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

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.stream.JsonReader;

import de.intranda.goobi.plugins.bsz.BSZ_Kultur_Element;
import de.sub.goobi.config.ConfigurationHelper;
import de.sub.goobi.forms.MassImportForm;
import de.sub.goobi.helper.exceptions.ImportPluginException;
import de.unigoettingen.sub.search.opac.ConfigOpac;
import de.unigoettingen.sub.search.opac.ConfigOpacCatalogue;
import lombok.extern.log4j.Log4j;
import net.xeoh.plugins.base.annotations.PluginImplementation;
import ugh.dl.DigitalDocument;
import ugh.dl.DocStruct;
import ugh.dl.DocStructType;
import ugh.dl.Fileformat;
import ugh.dl.Metadata;
import ugh.dl.Prefs;
import ugh.exceptions.MetadataTypeNotAllowedException;
import ugh.exceptions.PreferencesException;
import ugh.exceptions.TypeNotAllowedAsChildException;
import ugh.exceptions.TypeNotAllowedForParentException;
import ugh.exceptions.UGHException;
import ugh.exceptions.WriteException;
import ugh.fileformats.mets.MetsMods;

@PluginImplementation
@Log4j
public class ImportBodenseeZeitschriften_Kultur implements IImportPlugin, IPlugin {
	private static final String PLUGIN_NAME = "ImportBodenseeZeitschriften_Kultur";

	private static final String IMPORT_FOLDER = "/Users/steffen/Desktop/BSZ/";
	private static final String IMAGE_FOLDER_EXTENSION = "_" + ConfigurationHelper.getInstance().getMediaDirectorySuffix();
	private static final String IMAGE_FILE_PREFIX_TO_REMOVE = "/data/kebweb/kult/";
	//SQL converted to JSON simply using http://codebeautify.org/sql-to-json-converter
	private static final String IMPORT_FILE = IMPORT_FOLDER + "import_kultur.json";
	
	private MassImportForm form;
	private Prefs prefs;
	private String data = "";
	private String importFolder = "";
	private String ats = "kultur";
	private String catalogue = "BSZ-BW";
	private String ppn = "310869048";
	private String ppn_volume;
	
	@Override
	public List<String> getAllFilenames() {
		// read json file to list all years
		LinkedHashSet<String> years = new LinkedHashSet<String>();
		try {
			JsonReader reader = new JsonReader(new FileReader(IMPORT_FILE));
			Gson gson = new GsonBuilder().create();
			reader.beginArray();
			while (reader.hasNext()) {
				BSZ_Kultur_Element element = gson.fromJson(reader, BSZ_Kultur_Element.class);
				years.add(element.getJahr());
			}
			reader.close();
		} catch (IOException e) {
			log.error("Problem occured while reading the json file for kultur import", e);
		}
		return new ArrayList<String>(years);
	}
	
	@Override
	public List<ImportObject> generateFiles(List<Record> records) {
		List<ImportObject> answer = new ArrayList<ImportObject>();
		
		// run through all selected records and start to prepare the import
		for (Record record : records) {
			// updatae the progress bar for each record
			form.addProcessToProgressBar();
			
			// generate an ImportObject for each selected year and add it to the result list later on
			ImportObject importObjectYear = new ImportObject();
			ppn_volume = ppn + "_" + record.getId();
			String metsFileName = getImportFolder() + ppn_volume + ".xml";
			importObjectYear.setMetsFilename(metsFileName);
			importObjectYear.setProcessTitle("kultur_" + ppn_volume);
			try {
				// request the object from the catalogue and generate a FileFormat
				Fileformat fileformat = convertData();
				if (fileformat != null) {
					
					// create physical docstruct
					try {
						DigitalDocument dd = fileformat.getDigitalDocument();
						DocStructType docstructBoundBook = prefs.getDocStrctTypeByName("BoundBook");
						DocStruct physical = dd.createDocStruct(docstructBoundBook);
						Metadata pathimagefiles = new Metadata(prefs.getMetadataTypeByName("pathimagefiles"));
						pathimagefiles.setValue(ppn_volume);
						physical.addMetadata(pathimagefiles);
						dd.setPhysicalDocStruct(physical);
					
					} catch (PreferencesException | TypeNotAllowedForParentException
					        | MetadataTypeNotAllowedException e1) {
						log.error("Errow while doing the massimport in ImportBodenseeZeitschriften_Kultur", e1);
					}
					
					try {
						// add all issues and the correct pages there
						addAllIssues(fileformat, record.getId(),importObjectYear.getProcessTitle());
						
						// write Mets file into temp folder of Goobi to let it be imported afterwards
						MetsMods mm = new MetsMods(this.prefs);
						mm.setDigitalDocument(fileformat.getDigitalDocument());
						log.debug("Writing '" + metsFileName + "' into given folder...");
						mm.write(metsFileName);
						
						importObjectYear.setImportReturnValue(ImportReturnValue.ExportFinished);
					} catch (IOException e) {
						log.error("IOException during the massimport in ImportBodenseeZeitschriften_Kultur", e);
						importObjectYear.setErrorMessage(e.getMessage());
						importObjectYear.setImportReturnValue(ImportReturnValue.InvalidData);
					} catch (WriteException e) {
						log.error("WriteException during the massimport in ImportBodenseeZeitschriften_Kultur", e);
						importObjectYear.setErrorMessage(e.getMessage());
						importObjectYear.setImportReturnValue(ImportReturnValue.WriteError);
					} catch (UGHException e) {
						log.error("PreferencesException during the massimport in ImportBodenseeZeitschriften_Kultur", e);
						importObjectYear.setErrorMessage(e.getMessage());
						importObjectYear.setImportReturnValue(ImportReturnValue.InvalidData);
					}
				} else {
					importObjectYear.setImportReturnValue(ImportReturnValue.InvalidData);
				}
				
				answer.add(importObjectYear);
			} catch (ImportPluginException e) {
				log.error("ImportPluginException during the massimport in ImportBodenseeZeitschriften_Kultur", e);
			}
		}
		return answer;
	}

	
	/**
	 * add all issues and pages to the volume
	 * @param ff
	 */
	private void addAllIssues(Fileformat ff, String inYear, String inProcessTitle) throws IOException, UGHException{
		File targetFolderImages = new File(getImportFolder() + ppn_volume + File.separator + "images" 
        		+ File.separator + inProcessTitle + IMAGE_FOLDER_EXTENSION);
		File targetFolderPdf = new File(getImportFolder() + ppn_volume + File.separator + "ocr" 
        		+ File.separator);
        targetFolderImages.mkdirs();
		targetFolderPdf.mkdirs();
		
		DocStruct volume = ff.getDigitalDocument().getLogicalDocStruct().getAllChildren().get(0);
		DocStructType issueType = prefs.getDocStrctTypeByName("PeriodicalIssue");
		DocStruct issue = null;
		String lastIssue = "";
		
		JsonReader reader = new JsonReader(new FileReader(IMPORT_FILE));
		Gson gson = new GsonBuilder().create();
		reader.beginArray();
		while (reader.hasNext()) {
			BSZ_Kultur_Element element = gson.fromJson(reader, BSZ_Kultur_Element.class);
			// add all issues and pages from one year to this volume
			if (element.getJahr().equals(inYear)){
				
				// create new issue docstruct if necessary
				if (!lastIssue.equals(element.getBookletid())){
					lastIssue = element.getBookletid();
					issue = ff.getDigitalDocument().createDocStruct(issueType);
					
					// add title to issue
					Metadata issueTitleMd = new Metadata(prefs.getMetadataTypeByName("TitleDocMain"));
					issueTitleMd.setValue(element.getBookletid().substring(element.getBookletid().lastIndexOf(".")));
					issue.addMetadata(issueTitleMd);
					volume.addChild(issue);
					
					// copy pdf file into right place in tmp folder
					File pdfFile = new File(IMPORT_FOLDER, element.getPageid().substring(0, element.getPageid().lastIndexOf("_")) + ".pdf");
					if (pdfFile.exists()){
						FileUtils.copyFile(pdfFile, new File(targetFolderPdf, inProcessTitle + ".pdf"));
					}
				}
				
				// copy each image into right place in tmp folder
				File imageFile = new File(IMPORT_FOLDER, element.getJpg().substring(IMAGE_FILE_PREFIX_TO_REMOVE.length() -1));
				log.info("copy image from " + imageFile.getAbsolutePath() + " to " + targetFolderImages.getAbsolutePath());
				FileUtils.copyFile(imageFile, new File(targetFolderImages, imageFile.getName()));
				
				// no matter if new or current issue, add now all pages to current issue
				if (issue!=null){
					
				}
			}
		}
		reader.close();
	}
	
	@Override
    public Fileformat convertData() throws ImportPluginException {
		try {
			ConfigOpacCatalogue coc = ConfigOpac.getInstance().getCatalogueByName(catalogue);
			IOpacPlugin myImportOpac = (IOpacPlugin) PluginLoader.getPluginByTitle(PluginType.Opac, coc.getOpacType());
			Fileformat myRdf = myImportOpac.search("12", ppn, coc, prefs);
			if (myRdf != null) {
				try {
					ats = myImportOpac.createAtstsl(myRdf.getDigitalDocument().getLogicalDocStruct()
					        .getAllMetadataByType(prefs.getMetadataTypeByName("TitleDocMain")).get(0).getValue(), null)
					        .toLowerCase();
				} catch (Exception e) {
					ats = "";
				}
			}
			
			// assign a ppn digital to the anchor docstruct
			DocStruct ds = myRdf.getDigitalDocument().getLogicalDocStruct();
			Metadata md = new Metadata(prefs.getMetadataTypeByName("CatalogIDDigital"));
			md.setValue(ppn);
			ds.addMetadata(md);
			
			// assign a ppn digital to the child docstruct (volume)
			if (ds.getType().isAnchor()) {
				DocStruct child = ds.getAllChildren().get(0);
				Metadata md2 = new Metadata(prefs.getMetadataTypeByName("CatalogIDDigital"));
				md2.setValue(ppn_volume);
				child.addMetadata(md2);
			}
			
			return myRdf;
		} catch (Exception e) {
			log.error("Exception while creating the FileFormat", e);
		}
		return null;
	}
	
//	private List<BSZ_Kultur_Element> getKulturElements(){
//		Gson gson = new Gson();
//		BufferedReader br = new BufferedReader(new FileReader(IMPORT_FILE));
////		Type type = new TypeToken<List<Model>>(){}.getType();
//		List<BSZ_Kultur_Element> elements = gson.fromJson(br, List<BSZ_Kultur_Element>);
//		
//		
////		try(Reader reader = new InputStreamReader(JsonToJava.class.getResourceAsStream("/Server1.json"), "UTF-8")){
////            
////        	Gson gson = new GsonBuilder().create();
////            Person p = gson.fromJson(reader, Person.class);
////            System.out.println(p);
////        }
//	}
	
	


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
	public void deleteFiles(List<String> selectedFilenames) {
		// for (String filename : selectedFilenames) {
		// File f = new File(ROOT_FOLDER, filename);
		// // TODO delete
		// logger.debug("Delete folder " + f.getAbsolutePath());
		// }
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
	public PluginType getType() {
		return PluginType.Import;
	}

	@Override
	public String getTitle() {
		return PLUGIN_NAME;
	}

	@Override
	public void setPrefs(Prefs prefs) {
		this.prefs = prefs;
	}

	@Override
	public void setData(Record r) {
		data = r.getData();
	}

	@Override
	public String getImportFolder() {
		return this.importFolder;
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

	@Override
	public String getProcessTitle() {
		return ats;
	}
	
	// public static void main(String[] args) {
	//
	// String path = "/home/robert/Downloads/bsz/007140975";
	//
	// List<String> values = validate(path);
	// if (!values.isEmpty()) {
	// for (String message : values) {
	// logger.error(message);
	// }
	// } else {
	// logger.debug("Folder " + path + " is valid.");
	// }
	// ImageNameImportPlugin plugin = new ImageNameImportPlugin();
	// for (String filename : plugin.getAllFilenames()) {
	// System.out.println(filename);
	// }
	//
	// }

	@Override
	public void setForm(MassImportForm form) {
		this.form = form;

	}
}
