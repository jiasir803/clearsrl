package clearcommon.propbank;

import clearcommon.treebank.ParseException;
import clearcommon.treebank.TBTree;
import clearcommon.treebank.TreeFileResolver;
import clearcommon.util.FileUtil;

import java.io.File;
import java.io.FileNotFoundException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.regex.Pattern;

public final class PBUtil {
    
    public static Map<String, SortedMap<Integer, List<PBInstance>>> readPBDir(String dirName, String regex, String tbDir)
    {
        return readPBDir(dirName, regex, tbDir, null);
    }
    
    public static Map<String, SortedMap<Integer, List<PBInstance>>> readPBDir(String dirName, String regex, String tbDir, TreeFileResolver resolver)
    {
        Map<String, TBTree[]> tbMap = new TreeMap<String, TBTree[]>();
        return readPBDir(dirName, regex, tbDir, tbMap, resolver);
    }
    
	public static Map<String, SortedMap<Integer, List<PBInstance>>> readPBDir(String dirName, String regex, String tbDir, Map<String, TBTree[]> tbMap, TreeFileResolver resolver)
	{   
		File dir = new File(dirName);
		
		List<String> files = FileUtil.getFiles(dir, regex);
		
		if (!dir.isDirectory() && Pattern.matches(regex, dir.getName()))
			files.add(dir.getName());
		
		int correctCnt=0;
		int exceptionCnt=0;
		
		Map<String, SortedMap<Integer, List<PBInstance>>> pbMap = new TreeMap<String, SortedMap<Integer, List<PBInstance>>>();
		SortedMap<Integer, List<PBInstance>> instances;
		
		Set<String> predicates = new HashSet<String>();
		int pCnt = 0;
		
		for (String annotationFile: files)
		{
			PBReader pbreader=null;
            try {
                pbreader = new PBReader(dirName+File.separatorChar+annotationFile, tbDir, tbMap, resolver);
            } catch (FileNotFoundException e1) {
                e1.printStackTrace();
                continue;
            }
			System.out.println("Reading "+dirName+File.separatorChar+annotationFile);
			PBInstance instance=null;
			//System.out.println(annotationFile);
			for (;;)
			{
				try {
					instance = pbreader.nextProp();
				} catch (PBFormatException e) {
					++exceptionCnt;
				    e.printStackTrace();
				    continue;
				} catch (ParseException e) {
				    e.printStackTrace();
				    pbreader.close();
				    break;
				} catch (Exception e) {
					System.err.print(annotationFile+": ");
                    e.printStackTrace();
					continue;
				}
				
				if (instance==null) break;
				++correctCnt;
				
				predicates.add(instance.rolesetId);
				++pCnt;
				
				//System.out.println(instance.treeFile+" "+instance.treeIndex+" "+instance.predicateIndex);
				instances = pbMap.get(instance.tree.getFilename());
				if (instances == null)
				{
					instances = new TreeMap<Integer, List<PBInstance>>();
					pbMap.put(instance.tree.getFilename(), instances);
				}
				
				List<PBInstance> instanceList = instances.get(instance.tree.getIndex());
				if (instanceList == null)
				{
				    instanceList = new ArrayList<PBInstance>();
				    instances.put(instance.tree.getIndex(), instanceList);
				}
				instanceList.add(instance);
				
				//instance.tree.moveToRoot();
				//String[] sent = instance.tree.getSubTokens();				
				//for (String word:sent)
				//	System.out.print(word+" ");
				//System.out.print("\n");	
			}
		}
		System.out.printf("%d props read, %d format exceptions encountered\n", correctCnt, exceptionCnt);
		
		return pbMap;		
	}
}
