package edu.colorado.clear.common.propbank;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.Iterator;
import java.util.List;
import java.util.logging.Logger;
import java.util.regex.Pattern;

import edu.colorado.clear.common.treebank.*;

public class PBArg implements Comparable<PBArg>, Serializable {
    /**
     * 
     */
    private static final long serialVersionUID = 3167382729194529990L;
 
    private static Logger logger = Logger.getLogger(PBFileReader.class.getPackage().getName());
    
    //static final String LABEL_PATTERN = "(R-)?((A[A-Z]*\\d)(\\-[A-Za-z]+)?|[A-Za-z]+(\\-[A-Za-z]+)?)";
    //static final String ARG_PATTERN = "\\d+:\\d+([\\*,;&]\\d+:\\d+)*-[A-Za-z].*";
    
    static final Pattern ARG_PATTERN = Pattern.compile("(?<locs>\\d+:\\d+([\\*,;&]\\d+:\\d+)*)-(?<label>(R-)?((A[A-Z]*\\d)(\\-[A-Za-z]+)?|[A-Za-z]+(\\-[A-Za-z]+)?))(\\|(?<prob>[-+]?[0-9]*\\.?[0-9]+([eE][-+]?[0-9]+)?))?");
    
    static final PBArg[] NO_ARGS = new PBArg[0];
    
    String       label;
    double       score;
    TBNode       node;
    TBNode[]     allNodes;
    PBArg        linkingArg;
    PBArg[]      nestedArgs;
       
    TBNode[]     tokenNodes;
    

    BitSet       terminalSet;
    BitSet       tokenSet;

    public PBArg(String label) {
        this(label, 1.0);
    }
    
    public PBArg(String label, double score) {
        this.label = label.intern();
        this.score = score;
        node       = null;
        linkingArg = null;
        nestedArgs = NO_ARGS;
    }
    
    PBArg processNodes() throws PBFormatException {       
        //System.out.print(label+":");
        //for (TBNode node:tokenNodes)
        //    System.out.print(" "+node.toParse());
        //System.out.print("\n");
        
    	PBArg extraArg = null;    	
    	
    	// link traces
        List<TBNode> mainNodes = new ArrayList<TBNode>(Arrays.asList(allNodes));

        TBNode traceNode;
        boolean addedNodes;
        do {
            addedNodes = false;
            for (int i=0; i<mainNodes.size();++i) {
                if ((traceNode=mainNodes.get(i).getTrace())!=null) {
                    boolean found = false;
                    for (TBNode mainNode:mainNodes)
                        if (mainNode == traceNode) {
                            found = true;
                            break;
                        }
                    // if it's a reference arg, make sure the trace node doesn't trace back to the main argument
                    if (linkingArg!=null && traceNode==linkingArg.node)
                        found = true;
                    if (!found) {
                        mainNodes.add(traceNode);
                        addedNodes = true;
                        break;
                    }
                }
            }
        } while (addedNodes);
        
        for (int i=0; i<mainNodes.size(); ++i)
            for (int j=i+1; j<mainNodes.size(); ++j)
                if (mainNodes.get(i)==mainNodes.get(j)) {
                    mainNodes.remove(j);
                    continue;
                }
        
        for (int i=0; i<mainNodes.size(); ++i)
            for (int j=i+1; j<mainNodes.size(); ++j)
                if (mainNodes.get(j).isDecendentOf(mainNodes.get(i))) {
                    mainNodes.remove(j);
                    continue;
                } else if (mainNodes.get(i).isDecendentOf(mainNodes.get(j))) {
                	mainNodes.set(i, mainNodes.get(j));
                	mainNodes.remove(j);
                	continue;
                }
        
    	// check for reference args
    	if (!label.startsWith("R-")) {
    		boolean hasNoneWHNode = false;
    		TBNode rNode = null;
    		for (TBNode node:mainNodes) {
    			if (!node.isEC() && !node.getTokenSet().isEmpty()) {
    				if (node.getPOS().startsWith("WH"))
    					rNode = node;
    				else
    					hasNoneWHNode = true;
    			}
    		}
    		if (rNode!=null && hasNoneWHNode) {
    			extraArg = new PBArg("R-"+label);
    			extraArg.linkingArg = this;
    			
    			List<TBNode> extraNodes = new ArrayList<TBNode>();
    			extraNodes.add(rNode);

    			//TBNode[] tmp = new TBNode[allNodes.length-1];
    			//int cnt = 0;
    			
    			boolean removed = false;
    			do {
    				removed = false;
	    			for (Iterator<TBNode> iter=mainNodes.iterator(); iter.hasNext();) {
	    				TBNode node = iter.next();
	    				for (TBNode eNode:extraNodes)
		    				if (node==eNode || node.traceTo(eNode)) {
		    					iter.remove();
		    					removed = true;
		    					if (node!=eNode)
		    						extraNodes.add(node);
		    					break;
		    				}
	    				if (removed)
	    					break;
	    			}
    			} while(removed);
    			
    			extraArg.allNodes = extraNodes.toArray(new TBNode[extraNodes.size()]);
    		}
    	}
        
        allNodes = mainNodes.toArray(new TBNode[mainNodes.size()]);

        // assign single node to represent this argument
        node = null;
        
        if (mainNodes.size()==1)
            node = mainNodes.get(0);
        // just find the WH node for R- arguments
        else if (label.startsWith("R-")) {
            for (TBNode aNode:mainNodes)
                if (aNode.getPOS().startsWith("WH")) {
                    node = aNode;
                    allNodes = new TBNode[]{node};
                    break;
                }
            if (node==null) {
                node = mainNodes.get(0);
                StringBuilder builder = new StringBuilder(":");
                for (TBNode aNode:mainNodes)
                    builder.append(" "+aNode.toParse());
                logger.warning("Didn't find WH node for "+label+builder.toString());
            }
        } else {
            for (TBNode mainNode:mainNodes)
                if (!mainNode.isEC() && !mainNode.getTokenSet().isEmpty()) {
                    if (node!=null) {   
                        StringBuilder builder = new StringBuilder();
                        for (TBNode aNode:mainNodes)
                            builder.append("\n    "+aNode.toParse());
                        throw new PBFormatException(label+": multiple non-EC node detected"+builder.toString());
                    }
                    node = mainNode;
                }
            // We have an empty argument
            if (node==null) node = mainNodes.get(0);
        }
        
        // populate token nodes, etc
        terminalSet = node.getTerminalSet();
        tokenSet = node.getTokenSet();
        
        for (PBArg nestedArg:nestedArgs)
            nestedArg.processNodes();
        
        List<PBArg> nestedArgList = new ArrayList<PBArg>(Arrays.asList(nestedArgs));
        for (Iterator<PBArg> iter=nestedArgList.iterator(); iter.hasNext();)
        	if (iter.next().node==node)
        		iter.remove();
        for (int i=0; i<nestedArgList.size()-1; ++i)
            for (int j=i+1; j<nestedArgList.size();) {
                if (nestedArgList.get(i).node==nestedArgList.get(j).node)
                    nestedArgList.remove(j);
                else
                    ++j;
            }
        
        nestedArgs = nestedArgList.toArray(NO_ARGS);
        
        for (PBArg nestedArg:nestedArgs) {
            if (terminalSet.intersects(nestedArg.terminalSet)) {
                StringBuilder builder = new StringBuilder();
                builder.append("\n    "+node.toParse());
                for (PBArg arg: nestedArgs) {
                    builder.append("\n    "+arg.node.toParse());
                }
                throw new PBFormatException(label+": terminal overlap detected"+builder.toString());
            }
            terminalSet.or(nestedArg.terminalSet);
            tokenSet.or(nestedArg.tokenSet);
        }
        Arrays.sort(nestedArgs);
        
        List<TBNode>tNodes = node.getTokenNodes();
        for (PBArg nestedArg:nestedArgs)
            tNodes.addAll(Arrays.asList(nestedArg.tokenNodes));
        
        tokenNodes = tNodes.toArray(new TBNode[tNodes.size()]);
        
        return extraArg;
    }

    public boolean isLabel(String label) {
        return this.label.equals(label);
    }
    
    public String getLabel() {
        return label;
    }
    
    public String getBaseLabel() {
        String l = (label.startsWith("R-") || label.startsWith("C-"))?label.substring(2):label;
        
        return l.length()>4?l.substring(0, 4):l;
    }
    
    public double getScore() {
    	return score;
    }
    
    public boolean isPredicate() {
        return label.equals("rel");
    }
    
    public boolean isMainArg() {
        return isPredicate() || label.equals("ARG0") || label.equals("ARG1");
    }
    
    public boolean hasTerminal(int terminalIndex) {
        return terminalSet.get(terminalIndex);
    }
    
    public boolean hasToken(int tokenIndex) {
        return false;
    }
    
    public TBNode getNode() {
        return node;
    }
    
    public TBNode[] getAllNodes() {
        return allNodes;
    }
    
    public PBArg[] getNestedArgs() {
        return nestedArgs;
    }
    
    public TBNode[] getTerminalNodes() {
        ArrayList<TBNode> tnodes = new ArrayList<TBNode>();
        
        for (TBNode aNode:allNodes)
            tnodes.addAll(aNode.getTerminalNodes());

        for (PBArg nestedArg:nestedArgs)
            tnodes.addAll(nestedArg.node.getTerminalNodes());

        return tnodes.toArray(new TBNode[tnodes.size()]);
    }
    
    public TBNode[] getTokenNodes() {
        return tokenNodes;
    }

    public BitSet getTerminalSet() {
        return (BitSet)terminalSet.clone();
    }

    public BitSet getTokenSet() {
        return (BitSet)tokenSet.clone();
    }

    @Override
    public String toString() {
        StringBuilder str = new StringBuilder(label + ": ");
        
        if (tokenNodes.length>0) {
            str.append(tokenNodes[0].getWord()+" ");
            for (int i=1; i<tokenNodes.length; ++i) {
                if (tokenNodes[i-1].getTokenIndex()+1!=tokenNodes[i].getTokenIndex()) 
                    str.append("|");
                str.append(tokenNodes[i].getWord()+" ");
            }
        }
        return str.toString();
    }
    
    public boolean isEmpty() {
        return tokenSet.isEmpty();
    }

    @Override
    public int compareTo(PBArg o) {
        return terminalSet.nextSetBit(0)-o.terminalSet.nextSetBit(0);
    }
}
