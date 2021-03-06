package edu.colorado.clear.srl.align;

import gnu.trove.iterator.TIntObjectIterator;
import gnu.trove.map.TIntObjectMap;
import gnu.trove.set.TIntSet;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;
import java.io.PrintStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Scanner;
import java.util.Set;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.TreeSet;

import edu.colorado.clear.common.propbank.DefaultPBTokenizer;
import edu.colorado.clear.common.propbank.OntoNotesTokenizer;
import edu.colorado.clear.common.propbank.PBInstance;
import edu.colorado.clear.common.propbank.PBUtil;
import edu.colorado.clear.common.treebank.TBNode;
import edu.colorado.clear.common.treebank.TBReader;
import edu.colorado.clear.common.treebank.TBTree;
import edu.colorado.clear.common.treebank.TBUtil;
import edu.colorado.clear.srl.align.SentencePair.BadInstanceException;

public class LDCSentencePairReader extends SentencePairReader {

    Map<String, TBTree[]> srcTreeBank;
    Map<String, TBTree[]> dstTreeBank;
    Map<String, SortedMap<Integer, List<PBInstance>>>  srcPropBank;
    Map<String, SortedMap<Integer, List<PBInstance>>>  dstPropBank;
    
    Set<String> excludeFiles;
    
    Scanner sentenceInfoScanner;
    Scanner srcTokenScanner;
    Scanner dstTokenScanner;
    Scanner srcAlignmentScanner;
    Scanner dstAlignmentScanner;
    
    PrintStream srcTokenOutput;
    PrintStream dstTokenOutput;
    
    PrintStream sentenceInfoOutput;
    
    boolean bidirectionalAlignemnt;
    
    int count;
    
    public LDCSentencePairReader(Properties props) {
        this(props, false);
    }

    public LDCSentencePairReader(Properties props, boolean reWriteObjStream) {
        super(props, reWriteObjStream);

        excludeFiles = new TreeSet<String>();
    }
    
    @Override
    protected void finalize() throws Throwable {
        try {
            close();
        } finally {
            super.finalize();
        }
    }
    
    @Override
    public void initialize() throws IOException, InstantiationException, IllegalAccessException, ClassNotFoundException
    {
        close();
        super.initialize();
        if (objStreamAvailable) return;

        count = 0;
        
        srcTreeBank = TBUtil.readTBDir(props.getProperty("src.tbdir"), props.getProperty("tb.regex"));
        dstTreeBank = TBUtil.readTBDir(props.getProperty("dst.tbdir"), props.getProperty("tb.regex"));
        
        sentenceInfoScanner = new Scanner(new BufferedReader(new FileReader(props.getProperty("info"))));
        srcTokenScanner = new Scanner(new BufferedReader(new FileReader(props.getProperty("src.tokenfile")))).useDelimiter("[\n\r]");
        dstTokenScanner = new Scanner(new BufferedReader(new FileReader(props.getProperty("dst.tokenfile")))).useDelimiter("[\n\r]");
        
        if (props.getProperty("alignment")!=null)
        {
            bidirectionalAlignemnt = true;
            
            srcPropBank = PBUtil.readPBDir(props.getProperty("src.pbdir"), props.getProperty("pb.regex"), new TBReader(srcTreeBank), new OntoNotesTokenizer());
            dstPropBank = PBUtil.readPBDir(props.getProperty("dst.pbdir"), props.getProperty("pb.regex"), new TBReader(dstTreeBank), new OntoNotesTokenizer());
             
            srcAlignmentScanner = new Scanner(new BufferedReader(new FileReader(props.getProperty("alignment"))));
            srcTokenOutput = new PrintStream(props.getProperty("src.tokens"));
            dstTokenOutput = new PrintStream(props.getProperty("dst.tokens"));
            
            sentenceInfoOutput = new PrintStream(props.getProperty("info_out"));
        }
        else
        {
            srcPropBank = PBUtil.readPBDir(props.getProperty("src.pbdir"), props.getProperty("pb.regex"), new TBReader(srcTreeBank), props.getProperty("goldpb")==null?new DefaultPBTokenizer():new OntoNotesTokenizer());
            dstPropBank = PBUtil.readPBDir(props.getProperty("dst.pbdir"), props.getProperty("pb.regex"), new TBReader(dstTreeBank), props.getProperty("goldpb")==null?new DefaultPBTokenizer():new OntoNotesTokenizer());
            
            srcAlignmentScanner = new Scanner(new BufferedReader(new FileReader(props.getProperty("src.token_alignment")))).useDelimiter("[\n\r]");
            dstAlignmentScanner = new Scanner(new BufferedReader(new FileReader(props.getProperty("dst.token_alignment")))).useDelimiter("[\n\r]");
        }
    }
    
    @Override
    public SentencePair nextPair() {
        if (objStreamAvailable) return readSentencePair();

        if (!sentenceInfoScanner.hasNext()) return null;
        
        String info = sentenceInfoScanner.nextLine();
        
        String[] infoTokens = info.trim().split("[ \t]+");
        String[] srcTerminals = srcTokenScanner.nextLine().trim().split("[ \t]+");
        String[] dstTerminals = dstTokenScanner.nextLine().trim().split("[ \t]+");
        
        int id=Integer.parseInt(infoTokens[0]);
        SentencePair sentencePair = new SentencePair(count);
        
        List<String> srcWords = new ArrayList<String>();
        List<String> dstWords = new ArrayList<String>();

        int[] srcTerminaltoToken = new int[srcTerminals.length];
        for (int i=0; i<srcTerminals.length; ++i)
            if (!srcTerminals[i].startsWith("*"))  {
                srcTerminaltoToken[i] = srcWords.size();
                srcWords.add(srcTerminals[i]);
            } else {
                srcTerminaltoToken[i] = -1;
            }
        
        int[] dstTerminaltoToken = new int[dstTerminals.length];
        for (int i=0; i<dstTerminals.length; ++i)
            if (!dstTerminals[i].startsWith("*"))  {
                dstTerminaltoToken[i] = dstWords.size();
                dstWords.add(dstTerminals[i]);
            } else {
                dstTerminaltoToken[i] = -1;
            }

        String treeFilename = "nw/xinhua/"+infoTokens[1].substring(5,7)+"/"+infoTokens[1]+".parse";
        
        sentencePair.src = processSentence(treeFilename, infoTokens[3], srcTreeBank, srcPropBank);
        sentencePair.dst = processSentence(treeFilename, infoTokens[4], dstTreeBank, dstPropBank);
        
        if (sentencePair.src.indices.length != srcWords.size() || sentencePair.dst.indices.length != dstWords.size())
        {
            System.err.println("Mismatch: "+id+" "+Arrays.asList(infoTokens));
            
            sentencePair.id = -id;
            // read the alignment line off
            srcAlignmentScanner.nextLine();
            return sentencePair;
        }
        
        if (bidirectionalAlignemnt)
        {
            String[] alignmentStrs = srcAlignmentScanner.nextLine().trim().split("[ \t]+");
            
            srcTokenOutput.println(sentencePair.src.toTokens());
            dstTokenOutput.println(sentencePair.dst.toTokens());
                
            int[] srcAlignmentIdx = new int[alignmentStrs.length];
            int[] dstAlignmentIdx = new int[alignmentStrs.length];
            
            for (int i=0; i<alignmentStrs.length; ++i)
            {
                dstAlignmentIdx[i] = dstTerminaltoToken[Integer.parseInt(alignmentStrs[i].substring(0, alignmentStrs[i].indexOf('-')))];
                srcAlignmentIdx[i] = srcTerminaltoToken[Integer.parseInt(alignmentStrs[i].substring(alignmentStrs[i].indexOf('-')+1))];
            }
            
            sentencePair.setAlignment(srcAlignmentIdx, dstAlignmentIdx);

            excludeFiles.add(treeFilename);
            sentenceInfoOutput.println(info);
            
        } else {
             srcAlignmentScanner.next(); srcAlignmentScanner.next(); // skip comment & text
             dstAlignmentScanner.next(); dstAlignmentScanner.next(); // skip comment & text
             
             String srcLine = srcAlignmentScanner.next();
             String dstLine = dstAlignmentScanner.next();
             try {
                 sentencePair.parseSrcAlign(srcLine);
                 sentencePair.parseDstAlign(dstLine);
             } catch (BadInstanceException e) {
                 System.err.println(count);
                 e.printStackTrace();
                 System.err.println(srcLine);
                 System.err.println(dstLine);
             }
        }
        
        writeSentencePair(sentencePair);
        
        ++count;
        return sentencePair;
    }
    
    Sentence processSentence(String filename, 
            String treeIndices, 
            Map<String, TBTree[]> tbData, 
            Map<String, SortedMap<Integer, List<PBInstance>>> pbData)
    {
        
        StringBuilder builder = new StringBuilder();
        builder.append(filename);
        
        TBTree[] trees = tbData.get(filename);
        String[] treeIds = treeIndices.split(",");
        for (int i=0; i<treeIds.length; ++i)
        {
            TBTree tree =  trees[Integer.parseInt(treeIds[i])];
            for (TBNode node:tree.getTokenNodes())
                builder.append(" "+tree.getIndex()+'~'+node.getTerminalIndex());
        }
        
        return Sentence.parseSentence(builder.toString(), tbData, pbData, false);   
    }
    
    SortedMap<Long, int[]> convertAlignment(long[] indices, TIntObjectMap<TIntSet> inAlignment)
    {
        SortedMap<Long, int[]> outAlignment = new TreeMap<Long, int[]>();
        for (long index:indices)
            outAlignment.put(index, SentencePair.EMPTY_INT_ARRAY);
        
        for (TIntObjectIterator<TIntSet> iter = inAlignment.iterator(); iter.hasNext();)
        {
            iter.advance();
            if (!iter.value().isEmpty())
            {
                int[] iArray = iter.value().toArray();
                Arrays.sort(iArray);
                outAlignment.put(indices[iter.key()], iArray);
            }
        }
        
        return outAlignment;
    }
    
    Scanner closeScanner(Scanner scanner)
    {
        if (scanner!=null) {
            scanner.close();
            scanner = null;
        }
        return scanner;
    }
    
    PrintStream closeStream(PrintStream stream)
    {
        if (stream!=null) {
            stream.close();
            stream = null;
        }
        return stream;
    }
    
    @Override
    public void close()
    {
        sentenceInfoScanner = closeScanner(sentenceInfoScanner);
        srcTokenScanner     = closeScanner(srcTokenScanner);
        dstTokenScanner     = closeScanner(dstTokenScanner);
        srcAlignmentScanner = closeScanner(srcAlignmentScanner);
        dstAlignmentScanner = closeScanner(dstAlignmentScanner);
                
        if (!excludeFiles.isEmpty()) {
            try {
                PrintStream output = new PrintStream(props.getProperty("excludeFileList"));
                for (String filename:excludeFiles)
                    output.println(filename);
                output.close();
            } catch (Exception e) {
                e.printStackTrace();
            }
            excludeFiles.clear();
        }
        
        srcTokenOutput     = closeStream(srcTokenOutput);
        dstTokenOutput     = closeStream(dstTokenOutput);
        sentenceInfoOutput = closeStream(sentenceInfoOutput);
        
        super.close();
    }

}
