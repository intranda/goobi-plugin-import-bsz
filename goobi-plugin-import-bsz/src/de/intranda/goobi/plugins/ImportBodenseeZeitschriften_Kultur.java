package de.intranda.goobi.plugins;

import java.io.File;
import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

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
import ugh.exceptions.TypeNotAllowedForParentException;
import ugh.exceptions.WriteException;
import ugh.fileformats.mets.MetsMods;

@PluginImplementation
@Log4j
public class ImportBodenseeZeitschriften_Kultur implements IImportPlugin, IPlugin {
	private static final String PLUGIN_NAME = "ImportBodenseeZeitschriften_Kultur";
	private MassImportForm form;
	private Prefs prefs;
	private static final File ROOT_FOLDER = new File("/Users/steffen/Desktop/BSZ/");
	private String data = "";
	private String importFolder = "";
	private String ats = "kultur";
	private String currentIdentifier = "";
	private String catalogue = "BSZ-BW";
	private String ppn = "310869048";

	@Override
	public String getProcessTitle() {
		return ats + "_" + currentIdentifier;
	}

	@Override
	public List<ImportObject> generateFiles(List<Record> records) {
		List<ImportObject> answer = new ArrayList<ImportObject>();
		
		// run through all selected records and start to prepare the import
		for (Record record : records) {
			// updatae the progress bar for each record
			form.addProcessToProgressBar();
			
			// generate an ImportObject for each Record and add it to the result list later on
			ImportObject io = new ImportObject();
			currentIdentifier = record.getId();
			//String folder = ROOT_FOLDER.getAbsolutePath() + File.separator + currentIdentifier;
			String metsFileName = getImportFolder() + currentIdentifier + ".xml";
			io.setMetsFilename(metsFileName);
			io.setProcessTitle(getProcessTitle());
			
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
						pathimagefiles.setValue(currentIdentifier);
						physical.addMetadata(pathimagefiles);
						dd.setPhysicalDocStruct(physical);
					} catch (PreferencesException | TypeNotAllowedForParentException
					        | MetadataTypeNotAllowedException e1) {
						log.error("Errow while doing the massimport in ImportBodenseeZeitschriften_Kultur", e1);
					}
					
					// write Mets file into temp folder of Goobi to let it be imported afterwards
					try {
						MetsMods mm = new MetsMods(this.prefs);
						mm.setDigitalDocument(fileformat.getDigitalDocument());
						log.debug("Writing '" + metsFileName + "' into given folder...");
						mm.write(metsFileName);
						
						io.setImportReturnValue(ImportReturnValue.ExportFinished);
					} catch (PreferencesException e) {
						log.error("PreferencesException during the massimport in ImportBodenseeZeitschriften_Kultur", e);
						io.setErrorMessage(e.getMessage());
						io.setImportReturnValue(ImportReturnValue.InvalidData);
					} catch (WriteException e) {
						log.error("WriteException during the massimport in ImportBodenseeZeitschriften_Kultur", e);
						io.setErrorMessage(e.getMessage());
						io.setImportReturnValue(ImportReturnValue.WriteError);
					}
				} else {
					io.setImportReturnValue(ImportReturnValue.InvalidData);
				}
				answer.add(io);
			} catch (ImportPluginException e) {
				log.error("ImportPluginException during the massimport in ImportBodenseeZeitschriften_Kultur", e);
			}
		}
		return answer;
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
			
			// assign a ppn digital to the child docstruct
			if (ds.getType().isAnchor()) {
				DocStruct child = ds.getAllChildren().get(0);
				Metadata md2 = new Metadata(prefs.getMetadataTypeByName("CatalogIDDigital"));
				md2.setValue(ppn + "0001");
				child.addMetadata(md2);
			}
			
			return myRdf;
		} catch (Exception e) {
			log.error("Exception while creating the FileFormat", e);
		}
		return null;
	}
	
	@Override
	public List<String> getAllFilenames() {
		List<String> folderNames = new ArrayList<String>();
		try (DirectoryStream<Path> stream = Files.newDirectoryStream(Paths.get(ROOT_FOLDER.getAbsolutePath()))) {
			for (Path entry : stream) {
				if (Files.isDirectory(entry)) {
					folderNames.add(entry.toFile().getName());
				}
			}
		}catch (Exception e) {
			log.error("Error listing all folders in import root", e);
		}
		Collections.sort(folderNames);
		return folderNames;
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
