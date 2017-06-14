package de.intranda.goobi.plugins.bsz;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import org.apache.commons.io.FileUtils;

public class Play {
	public static void main(String[] args) throws IOException{
		List <BSZ_BodenseeImport_Element> elements = new ArrayList<>();
		
		File f = new File ("/opt/digiverso/BSZ/wbjb.sql");
		List<String> lines = FileUtils.readLines(f, "UTF-8");
		for (String line : lines) {
			if (line.startsWith("INSERT INTO")){
				line = line.substring(line.indexOf("("), line.indexOf(")"));
				String[] parts = line.split(",");
				BSZ_BodenseeImport_Element element = new BSZ_BodenseeImport_Element();
				element.setPageid(parts[0]);
				element.setBookletid(parts[1]);
				element.setJournalid(parts[2]);
				element.setLfnr(parts[3]);
				element.setJahr(parts[4]);
				element.setLabel(parts[5]);
				element.setJpg(parts[6]);
				elements.add(element);
			}
		}
		
		System.out.println("fertig mit elements");
		
	}
}
