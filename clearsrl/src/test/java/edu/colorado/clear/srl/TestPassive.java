package edu.colorado.clear.srl;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileReader;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.SortedMap;
import java.util.StringTokenizer;
import java.util.TreeMap;

import edu.colorado.clear.common.propbank.DefaultPBTokenizer;
import edu.colorado.clear.common.propbank.OntoNotesTokenizer;
import edu.colorado.clear.common.propbank.PBInstance;
import edu.colorado.clear.common.propbank.PBUtil;
import edu.colorado.clear.common.treebank.ParseException;
import edu.colorado.clear.common.treebank.SerialTBFileReader;
import edu.colorado.clear.common.treebank.TBFileReader;
import edu.colorado.clear.common.treebank.TBHeadRules;
import edu.colorado.clear.common.treebank.TBTree;
import edu.colorado.clear.common.treebank.TBUtil;
import edu.colorado.clear.common.util.LanguageUtil;
import edu.colorado.clear.srl.CoNLLSentence;
import edu.colorado.clear.srl.SRInstance;
import edu.colorado.clear.srl.SRLModel.Feature;

public class TestPassive {
    static final float THRESHOLD=0.8f;
    
    public static void main(String[] args) throws Exception
    {   
        Properties props = new Properties();
        FileInputStream in = new FileInputStream(args[0]);
        props.load(in);
        in.close();
        
        LanguageUtil langUtil = (LanguageUtil) Class.forName(props.getProperty("language.util-class")).newInstance();
        if (!langUtil.init(props))
            System.exit(-1);
        
        
        ArrayList<Feature> features = new ArrayList<Feature>();
        StringTokenizer tokenizer = new StringTokenizer(props.getProperty("feature"),",");
        while (tokenizer.hasMoreTokens())
        {
            try {
                features.add(Feature.valueOf(tokenizer.nextToken().trim()));
            } catch (IllegalArgumentException e) {
                System.err.println(e);
            }
        }
        System.out.println(EnumSet.copyOf(features));
        
        TBHeadRules headrules = new TBHeadRules(props.getProperty("headrules"));
        
        String dataFormat = props.getProperty("data.format", "default");
        
        if (!dataFormat.equals("conll"))
        {
            String trainRegex = props.getProperty("train.regex");
            //Map<String, TBTree[]> treeBank = TBUtil.readTBDir(props.getProperty("tbdir"), trainRegex);
            Map<String, SortedMap<Integer, List<PBInstance>>>  propBank = 
                PBUtil.readPBDir(props.getProperty("pbdir"), 
                                 trainRegex, 
                                 props.getProperty("tbdir"), 
                                 dataFormat.equals("ontonotes")?new OntoNotesTokenizer():new DefaultPBTokenizer());
            
            System.out.println(propBank.size());
            
            Map<String, TBTree[]> parsedTreeBank = new TreeMap<String, TBTree[]>();
            for (Map.Entry<String, SortedMap<Integer, List<PBInstance>>> entry:propBank.entrySet())
            {
                try {
                    System.out.println("Reading "+props.getProperty("parsedir")+File.separatorChar+entry.getKey());
                    TBFileReader tbreader    = new SerialTBFileReader(props.getProperty("parsedir")+File.separatorChar+entry.getKey());
                    ArrayList<TBTree> a_tree = new ArrayList<TBTree>();
                    TBTree tree;
                    while ((tree = tbreader.nextTree()) != null)
                        a_tree.add(tree);
                    parsedTreeBank.put(entry.getKey(), a_tree.toArray(new TBTree[a_tree.size()]));
                } catch (FileNotFoundException e) {
                    e.printStackTrace();
                } catch (ParseException e) {
                    e.printStackTrace();
                }
            }
            for (Map.Entry<String, TBTree[]> entry: parsedTreeBank.entrySet())
            {
                SortedMap<Integer, List<PBInstance>> pbFileMap = propBank.get(entry.getKey());
                TBTree[] trees = entry.getValue(); 
                for (int i=0; i<trees.length; ++i)
                {
                    TBUtil.linkHeads(trees[i], langUtil.getHeadRules());
                    List<PBInstance> pbInstances = pbFileMap.get(i);
                    if (pbInstances==null) continue;
                    for (PBInstance instance:pbInstances)
                    {
                        int passive;
                        if ((passive=langUtil.getPassive(instance.getPredicate()))!=0)
                        {
                            System.out.println(passive+": "+" "+new SRInstance(instance));
                            System.out.println("   "+instance.getTree().toString()+"\n");
                        }
                    }
                }
            }
        }
        else
        {       
            ArrayList<CoNLLSentence> training = CoNLLSentence.read(new FileReader(props.getProperty("train.input")), true);
            
            for (CoNLLSentence sentence:training)
            {
                TBUtil.linkHeads(sentence.parse, headrules);
                for (SRInstance instance:sentence.srls)
                {
                    if (instance.getPredicateNode().getPOS().matches("V.*"))
                    {
                        System.out.println(langUtil.getPassive(instance.getPredicateNode())+": "+" "+instance);
                        System.out.println("   "+instance.tree.toString()+"\n");
                    }
                }
            }
        }
    }

}
