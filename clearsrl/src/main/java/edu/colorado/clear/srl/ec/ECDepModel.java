package edu.colorado.clear.srl.ec;

import edu.colorado.clear.common.alg.Classifier;
import edu.colorado.clear.common.alg.CrossValidator;
import edu.colorado.clear.common.alg.LinearClassifier;
import edu.colorado.clear.common.propbank.PBArg;
import edu.colorado.clear.common.propbank.PBInstance;
import edu.colorado.clear.common.treebank.TBNode;
import edu.colorado.clear.common.treebank.TBTree;
import edu.colorado.clear.common.util.PBFrame.Roleset;
import edu.colorado.clear.srl.ec.ECCommon.Feature;
import edu.colorado.clear.srl.ec.ECCommon.LabelType;
import gnu.trove.list.TIntList;
import gnu.trove.list.array.TIntArrayList;
import gnu.trove.map.TIntObjectMap;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.TreeSet;

public class ECDepModel extends ECModel implements Serializable {
    /**
     * 
     */
    private static final long serialVersionUID = 1L;

    Classifier stage2Classifier;
    
    transient int propCnt;
    transient int propTotal;
    transient int elipsisCnt;
    transient int elipsisTotal;
    
    transient boolean quickClassify = false;
    transient boolean fullPredict = false;
    
    static class DepLabel implements Comparable<DepLabel> {
        
        TBNode head;
        boolean inFront;
        String label;
        
        public DepLabel(TBNode head, boolean inFront, String label) {
            this.head = head;
            this.inFront = inFront;
            this.label = label;
        }
        
        @Override
        public int compareTo(DepLabel rhs) {
            if (head==rhs.head) return 0;
            
            if (inFront!=rhs.inFront)
                return inFront?1:-1;
            if (head.getConstituentByHead().isDecendentOf(rhs.head.getConstituentByHead()))
                return inFront?1:-1;
            else if (rhs.head.getConstituentByHead().isDecendentOf(head.getConstituentByHead()))
                return inFront?-1:1;
            return inFront^(head.getTokenIndex()>rhs.head.getTokenIndex())?-1:1;
        }
    }
    
    public ECDepModel (Set<EnumSet<Feature>> featureSet) {
        this(featureSet, LabelType.ALL);
    }
    
    public ECDepModel (Set<EnumSet<Feature>> featureSet, LabelType labelType) {
        super(featureSet, labelType);
        propTotal = propCnt = 0;
        elipsisCnt = elipsisTotal = 0;
    }
    
    public void setFullPredict(boolean fullPredict) {
        this.fullPredict = fullPredict;
    }
    
    public void setQuickClassify(boolean quickClassify) {
    	this.quickClassify = quickClassify;
    }
    
    public EnumMap<Feature,List<String>> getHeadFeatures(TBNode head, PBInstance instance) {
        EnumMap<Feature,List<String>> sample = new EnumMap<Feature,List<String>>(Feature.class);
        return sample;
    }
    
    static String makePath(List<TBNode> nodes, boolean includeToken) {
        StringBuilder builder = new StringBuilder();
        for (int i=(includeToken?0:1); i<nodes.size()-(includeToken?0:1); ++i) {
            if (builder.length()!=0)
                builder.append(nodes.get(i-1).getParent()==nodes.get(i)?ECCommon.UP_CHAR:ECCommon.DOWN_CHAR);
            builder.append(nodes.get(i).getPOS());
        }
        return builder.toString();
    }
    
    static String makeDepPath(List<TBNode> nodes, boolean includeToken) {
        StringBuilder builder = new StringBuilder();
        for (int i=(includeToken?0:1); i<nodes.size()-(includeToken?0:1); ++i) {
            if (builder.length()!=0)
                builder.append(nodes.get(i-1).getHeadOfHead()==nodes.get(i)?ECCommon.UP_CHAR:ECCommon.DOWN_CHAR);
            builder.append(nodes.get(i).getDepLabel()==null?nodes.get(i).getPOS():nodes.get(i).getDepLabel());
        }
        return builder.toString();
    }
        
    public List<EnumMap<Feature,Collection<String>>> extractSampleFeature(TBTree tree, BitSet[] headMasks, List<PBInstance> pList, boolean buildDictionary) {
        List<EnumMap<Feature,Collection<String>>> samples = new ArrayList<EnumMap<Feature,Collection<String>>>();

        TBNode[] tokens = tree.getTokenNodes();
        String[] lemmas = new String[tokens.length];
        String[] poses = new String[tokens.length];
        for (int i=0; i<tokens.length;++i) {
            lemmas[i] = tokens[i].getWord();
            poses[i] = tokens[i].getPOS();
        }
        
        List<EnumMap<Feature,List<String>>> positionSamples = new ArrayList<EnumMap<Feature,List<String>>>();
        List<EnumMap<Feature,List<String>>> headSamples = new ArrayList<EnumMap<Feature,List<String>>>();
        
        BitSet extractHead = new BitSet(tokens.length);
        BitSet extractPosition = new BitSet(tokens.length+1);
        
        for (int h=0; h<tokens.length; ++h)
            extractHead.set(h, !headMasks[h].isEmpty());
        
        for (BitSet position:headMasks)
            extractPosition.or(position);

        PBInstance[] props = new PBInstance[tokens.length];
        if (pList!=null)
            for (PBInstance instance:pList)
                props[instance.getPredicate().getTokenIndex()] = instance;

        //Add head only features
        for (int t=0; t<tokens.length; ++t) {
            if (!extractHead.get(t)) {
                headSamples.add(null);
                continue;
            }
            
            TBNode token = tokens[t];
            TBNode head = tokens[t];
            

            /*
            if (head.getPOS().matches("BA|LB|SB")) {
                List<TBNode> nodes = head.getDependentNodes();
                if (nodes.size()==1 || nodes.get(0).getPOS().startsWith("V"))
                    head = nodes.get(0);
                else if (head.getChildIndex()==0 && head.getParent().getChildren().length==2)
                    head = head.getParent().getChildren()[1].getHead();
                else {
                    for (int c = head.getChildIndex(); c<head.getParent().getChildren().length; ++c)
                        if (head.getParent().getChildren()[c].isPos("VP")) {
                            head = head.getParent().getChildren()[c].getHead();
                            break;
                        }
                }
            }*/
            int h = head.getTokenIndex();
            PBInstance prop = props[t] = props[h];
            
            //if (chLangUtil.isVerb(head.getPOS()) && prop==null) {
            //  System.err.println("Missing prop for "+head.getWord());
            //	System.err.println(head.getConstituentByHead().toPrettyParse(0));
            //}
            
            EnumMap<Feature,List<String>> sample = new EnumMap<Feature,List<String>>(Feature.class);
            for (Feature feature:features.getFeaturesFlat())
                switch (feature) {
                case H_LEMMA:
                    sample.put(feature, ECCommon.getPartial(lemmas[h]));
                    break;
                case H_POS:
                    sample.put(feature, Arrays.asList(poses[h]));
                    break;
                case H_TOP_VERB:
                	if (chLangUtil.isVerb(poses[h])) {
                		boolean topVerb = true;
                		for (TBNode node:tokens[h].getDepPathToRoot())
                			if (node!=tokens[h] && chLangUtil.isVerb(node.getPOS())) {
                				topVerb = false;
                				break;
                			}
                		sample.put(feature, Arrays.asList(Boolean.toString(topVerb)));
                	}
                	break;
                case H_CONSTITUENT:
                	sample.put(feature, Arrays.asList(head.getConstituentByHead().getPOS()));
                case H_CONSTITUENT_PARENT:
                	if (head.getConstituentByHead().getParent()!=null)
                	sample.put(feature, Arrays.asList(head.getConstituentByHead().getParent().getPOS()));
                case H_CONSTITUENT_VP_FIRST:
                    if (head.getConstituentByHead()!=null && head.getConstituentByHead().getPOS().equals("VP")) {
                    	boolean isFirst=true;
                    	for (int i=0;i<head.getConstituentByHead().getChildIndex(); ++i)
                    		if (head.getConstituentByHead().getParent().getChildren()[i].getPOS().equals("VP")) {
                    			isFirst=false;
                    			break;
                    		}
                    	sample.put(feature, Arrays.asList(Boolean.toString(isFirst)));
                    } 
                    break;
                case H_ORG_LEMMA:
                    if (token!=head)
                        sample.put(feature, ECCommon.getPartial(lemmas[h]));
                    break;
                case H_ORG_POS:
                    if (token!=head)
                        sample.put(feature, Arrays.asList(poses[h]));
                    break;
                case H_ORG_SPATH: 
                    if (token!=head)
                        sample.put(feature, Arrays.asList(makePath(head.getConstituentByHead().getPath(token), true)));
                    break;
                case H_ORG_DPATH:  // dependency path from head to modified head
                	if (token!=head)
                        sample.put(feature, Arrays.asList(makeDepPath(head.getConstituentByHead().getDepPath(token), true)));
                    break;      
                case H_H_LEMMA:    // head of head
                    if (token.getHeadOfHead()!=null)
                        sample.put(feature, ECCommon.getPartial(lemmas[token.getHeadOfHead().getTokenIndex()]));
                    break;
                case H_H_POS:      // head of head lemma
                    if (token.getHeadOfHead()!=null)
                        sample.put(feature, Arrays.asList(token.getHeadOfHead().getPOS(), token.getHeadOfHead().getConstituentByHead().getPOS()));
                    break;
                case H_H_SPATH:    // syntactic path to head of head
                    if (token.getHeadOfHead()!=null)
                        sample.put(feature, Arrays.asList(makePath(token.getConstituentByHead().getPath(token.getHeadOfHead()), true)));
                    break;
                case H_SFRAME:
                {
                    StringBuilder builder = new StringBuilder();
                    for (TBNode node:head.getParent().getChildren()) {
                        if (node != head)
                            builder.append(node.getPOS().toLowerCase()+"-");
                        else
                            builder.append(node.getPOS().toUpperCase()+"-");
                    }
                    sample.put(feature, Arrays.asList(builder.toString()));
                    break;
                }
                case H_CONSTSFRAME:
                	if (head.getConstituentByHead().getParent()!=null) {
	                    StringBuilder builder = new StringBuilder();
	                    for (TBNode node:head.getConstituentByHead().getParent().getChildren()) {
	                        if (node != head.getConstituentByHead())
	                            builder.append(node.getPOS().toLowerCase()+"-");
	                        else
	                            builder.append(node.getPOS().toUpperCase()+"-");
	                    }
	                    sample.put(feature, Arrays.asList(builder.toString()));
                	}
                    break;
                case H_VOICE:
                    if (chLangUtil.isVerb(head.getPOS())) {
                   
                        int passive = chLangUtil.getPassive(head);
                        if (passive !=0)
                            sample.put(feature, Arrays.asList("passive", Integer.toString(passive)));
                        else
                            sample.put(feature, Arrays.asList("active"));
                    	//sample.put(feature, chLangUtil.getConstructionTypes(head));
                    }
                    break;
                case SRL_NONLOCAL_ARG:
                	if (prop!= null) {
                    	TBNode hConst = head.getConstituentByHead();
                    	List<String> args = new ArrayList<String>();
                        for (PBArg arg:prop.getAllArgs())
                            if (arg.getLabel().matches("ARG\\d") && !arg.getNode().isDecendentOf(hConst))
                                args.add(arg.getLabel());
                        sample.put(feature, args.isEmpty()?Arrays.asList(Boolean.toString(false)):args);
                    }
                	break;
                case SRL_FRAMEFILE:
                	if (prop!= null) {
                		Roleset roleSet = chLangUtil.getRoleSet(prop.getPredicate(), prop.getRoleset());
                		if (roleSet!=null) {
                			Set<String> roles = new HashSet<String>(roleSet.getRoles());
                			Set<String> foundRoles = new HashSet<String>();
                			for (PBArg arg:prop.getAllArgs())
                				if (roles.contains(arg.getLabel().toLowerCase())) {
                					foundRoles.add(arg.getLabel().toLowerCase());
                					roles.remove(arg.getLabel().toLowerCase());
                				}
                			List<String> featVals = new ArrayList<String>(roles.size()+foundRoles.size());
                			for (String role:roles)
                				featVals.add("m-"+role);
                			for (String role:foundRoles)
                				featVals.add("f-"+role);
                			if (!featVals.isEmpty())
                				sample.put(feature, featVals);
                		}
                		
                		/*
                		PBFrame frame = chLangUtil.frameMap.get(prop.getPredicate().getWord()+"-v");
                		if (frame!=null && frame.getRolesets().size()==1) {
                			Set<String> roles = new HashSet<String>(frame.getRolesets().values().iterator().next().getRoles());
                			Set<String> foundRoles = new HashSet<String>();
                			for (PBArg arg:prop.getAllArgs())
                				if (roles.contains(arg.getLabel().toLowerCase())) {
                					foundRoles.add(arg.getLabel().toLowerCase());
                					roles.remove(arg.getLabel().toLowerCase());
                				}
                			List<String> featVals = new ArrayList<String>(roles.size()+foundRoles.size());
                			for (String role:roles)
                				featVals.add("m-"+role);
                			for (String role:foundRoles)
                				featVals.add("f-"+role);
                			if (!featVals.isEmpty())
                				sample.put(feature, featVals);
                		}*/
                	}
                	break;
                case SRL_FRAMEFILE_LOCAL:
                	if (prop!= null) {
                		Roleset roleSet = chLangUtil.getRoleSet(prop.getPredicate(), prop.getRoleset());
                		if (roleSet!=null) {
                			Set<String> roles = new HashSet<String>(roleSet.getRoles());
                			Set<String> foundRoles = new HashSet<String>();
                			
                			TBNode hConst = head.getConstituentByHead();
                			for (PBArg arg:prop.getAllArgs())
                				if (arg.getNode().isDecendentOf(hConst) && roles.contains(arg.getLabel().toLowerCase())) {
                					foundRoles.add(arg.getLabel().toLowerCase());
                					roles.remove(arg.getLabel().toLowerCase());
                				}
                			List<String> featVals = new ArrayList<String>(roles.size()+foundRoles.size());
                			for (String role:roles)
                				featVals.add("m-"+role);
                			for (String role:foundRoles)
                				featVals.add("f-"+role);
                			if (!featVals.isEmpty())
                				sample.put(feature, featVals);
                		}
                		/*
                		PBFrame frame = chLangUtil.frameMap.get(prop.getPredicate().getWord()+"-v");
                		if (frame!=null && frame.getRolesets().size()==1) {
                			Set<String> roles = new HashSet<String>(frame.getRolesets().values().iterator().next().getRoles());
                			Set<String> foundRoles = new HashSet<String>();
                			
                			TBNode hConst = head.getConstituentByHead();
                			for (PBArg arg:prop.getAllArgs())
                				if (arg.getNode().isDecendentOf(hConst) && roles.contains(arg.getLabel().toLowerCase())) {
                					foundRoles.add(arg.getLabel().toLowerCase());
                					roles.remove(arg.getLabel().toLowerCase());
                				}
                			List<String> featVals = new ArrayList<String>(roles.size()+foundRoles.size());
                			for (String role:roles)
                				featVals.add("m-"+role);
                			for (String role:foundRoles)
                				featVals.add("f-"+role);
                			if (!featVals.isEmpty())
                				sample.put(feature, featVals);
                		}*/
                	}
                	break;
                case SRL_NOARG0:             // predicate has no ARG0
                    if (prop!= null) {
                        for (PBArg arg:prop.getAllArgs())
                            if (arg.getLabel().equals("ARG0")) {
                                sample.put(feature, Arrays.asList(Boolean.toString(false), arg.getNode().getPOS()));
                                break;
                            }
                        if (sample.get(feature)==null)
                            sample.put(feature, Arrays.asList(Boolean.toString(true)));
                    }
                    break;
                case SRL_NOARG1:             // predicate has no ARG1
                    if (prop!= null) {
                        for (PBArg arg:prop.getAllArgs())
                            if (arg.getLabel().equals("ARG1")) {
                                sample.put(feature, Arrays.asList(Boolean.toString(false), arg.getNode().getPOS()));
                                break;
                            }
                        if (sample.get(feature)==null)
                            sample.put(feature, Arrays.asList(Boolean.toString(true)));
                    }
                    break;
                case SRL_LOCAL_NOARG0:             // predicate has no local ARG0
                    if (prop!= null) {
                    	TBNode hConst = head.getConstituentByHead();
                        for (PBArg arg:prop.getAllArgs())
                            if (arg.getLabel().equals("ARG0") && arg.getNode().isDecendentOf(hConst)) {
                                sample.put(feature, Arrays.asList(Boolean.toString(false), arg.getNode().getPOS()));
                                break;
                            }
                        if (sample.get(feature)==null)
                            sample.put(feature, Arrays.asList(Boolean.toString(true)));
                    }
                    break;
                case SRL_LOCAL_NOARG1:             // predicate has no local ARG1
                    if (prop!= null) {
                    	TBNode hConst = head.getConstituentByHead();
                        for (PBArg arg:prop.getAllArgs())
                            if (arg.getLabel().equals("ARG1") && arg.getNode().isDecendentOf(hConst)) {
                                sample.put(feature, Arrays.asList(Boolean.toString(false), arg.getNode().getPOS()));
                                break;
                            }
                        if (sample.get(feature)==null)
                            sample.put(feature, Arrays.asList(Boolean.toString(true)));
                    }
                    break;
                case SRL_NOLEFTCOREARG:      // predicate has no core argument to its left
                    if (prop!= null) {
                        for (PBArg arg:prop.getArgs())
                            if (arg.getTerminalSet().nextSetBit(0)<prop.getPredicate().getTerminalIndex() 
                                    && arg.getLabel().matches("(R-)*ARG\\d")) {
                                sample.put(feature, Arrays.asList(Boolean.toString(false), arg.getLabel()));
                                break;
                            }
                        if (sample.get(feature)==null)
                            sample.put(feature, Arrays.asList(Boolean.toString(true)));
                    }
                    break;
                case SRL_NOLEFTNPARG:      // predicate has no NP argument to its left
                    if (prop!= null) {
                        for (PBArg arg:prop.getArgs())
                            if (arg.getTerminalSet().nextSetBit(0)<prop.getPredicate().getTerminalIndex() 
                                    && arg.getNode().getPOS().equals("NP")) {
                                sample.put(feature, Arrays.asList(Boolean.toString(false), arg.getLabel()));
                                break;
                            }
                        if (sample.get(feature)==null)
                            sample.put(feature, Arrays.asList(Boolean.toString(true)));
                    }
                    break;
                case SRL_FRAME:
                    if (prop!= null) {
                        boolean addedEC = false;
                        ArrayList<String> pathList = new ArrayList<String>();
                        
                        List<PBArg> frontList = new ArrayList<PBArg>();
                        List<PBArg> backList = new ArrayList<PBArg>();
                        
                        StringBuilder pathStr = new StringBuilder();
                        for (PBArg arg:prop.getArgs()) {
                            if (arg.getLabel().equals("rel")) {
                                if (!addedEC) {
                                    pathStr.append("_EC");
                                    addedEC = true;
                                }
                                break;
                            }
                            BitSet tokenSet = arg.getTokenSet();
                            if (tokenSet.nextSetBit(0)!=h && tokenSet.get(h)) {
                                addedEC = false;
                                break;
                            }
                            if (tokenSet.nextSetBit(0)>=h && addedEC==false) {
                                pathStr.append("_EC");
                                addedEC = true;
                                
                            }
                            if (addedEC)backList.add(arg);
                            else frontList.add(arg);
                            pathStr.append("_"+arg.getLabel());
                        }
                        if (addedEC){
                            for (PBArg arg:frontList) {
                                pathList.add("f-"+arg.getLabel());
                                pathList.add("f-"+arg.getNode().getPOS());
                                pathList.add("f-"+arg.getLabel()+"-"+arg.getNode().getPOS());
                                pathList.add("f-"+arg.getNode().getHeadword());
                            }
                            for (PBArg arg:backList) {
                                pathList.add("b-"+arg.getLabel());
                                pathList.add("b-"+arg.getNode().getPOS());
                                pathList.add("b-"+arg.getLabel()+"-"+arg.getNode().getPOS());
                                pathList.add("b-"+arg.getNode().getHeadword());
                            }
                            pathList.add(pathStr.toString());
                        }
                        
                        TBNode ancestor = head;
                        while (!ancestor.getPOS().matches("(IP|FRAG)") && ancestor.getParent()!=null && ancestor.getParent().getTokenSet().nextSetBit(0)==h)
                            ancestor = ancestor.getParent();
                        
                        if (ancestor.getPOS().matches("(IP|FRAG)")) {
                            if (ancestor.getHead()==prop.getPredicate()) {
                                pathStr = new StringBuilder(ancestor.getPOS()+"-ec");
                                StringBuilder pathStr2 = new StringBuilder(ancestor.getPOS()+"-ec");
                                for (int c=0; c<ancestor.getChildren().length; ++c) {
                                    PBArg foundArg = null;
                                    for (PBArg arg:prop.getArgs())
                                        if (arg.getNode()==ancestor.getChildren()[c]) {
                                            foundArg = arg;
                                            break;
                                        }
                                    String label = foundArg==null?ancestor.getChildren()[c].getPOS():foundArg.getLabel();
                                    if (!pathStr.toString().endsWith(label)) pathStr.append("-"+label);
                                    if (foundArg!=null && !pathStr2.toString().endsWith(label) || ancestor.getHead()==ancestor.getChildren()[c].getHead()) pathStr2.append("-"+label);
                                    
                                    if (ancestor.getHead()==ancestor.getChildren()[c].getHead()) break;
                                }
                                pathList.add(pathStr.toString()); pathList.add(pathStr2.toString()+"-2"); 
                            }   
                        } else if (ancestor.getParent()!=null) {
                            pathStr = new StringBuilder(ancestor.getParent().getPOS());
                            for (int c=0; c<ancestor.getParent().getChildren().length; ++c) {
                                if (c==ancestor.getChildIndex())
                                    pathStr.append("-ec");

                                PBArg foundArg = null;
                                for (PBArg arg:prop.getArgs())
                                    if (arg.getNode()==ancestor.getParent().getChildren()[c]) {
                                        foundArg = arg;
                                        break;
                                    }
                                String label = foundArg==null?ancestor.getParent().getChildren()[c].getPOS():foundArg.getLabel();
                                if (!pathStr.toString().endsWith(label)) pathStr.append("-"+label);
                                if (ancestor.getParent().getChildren()[c].getHead()==prop.getPredicate()) break;
                            }
                            pathList.add(pathStr.toString());           
                        }
                    
                        pathList.trimToSize();
                        if (!pathList.isEmpty()) sample.put(feature, pathList);
                    }
                    break;
                case SRL_LEFTARGTYPE:
                    if (prop!= null) {
                        List<String> argTypes = new ArrayList<String>();
                        for (PBArg arg:prop.getArgs())
                            if (h>0 && arg.getTokenSet().get(h-1) && arg.getTokenSet().get(h)) {
                                argTypes.clear();
                                break;
                            } else if (arg.getTokenSet().length()<=h)
                                argTypes.add(arg.getLabel());
                        if (argTypes.isEmpty()) sample.put(feature, argTypes);
                    }
                    break;
                default:
                    break;
                }
            headSamples.add(sample);
        }
        
        // Add position only features
        for (int t=0; t<=tokens.length; ++t) {
            if (!extractPosition.get(t)) {
                positionSamples.add(null);
                continue;
            }
            EnumMap<Feature,List<String>> sample = new EnumMap<Feature,List<String>>(Feature.class);
            for (Feature feature:features.getFeaturesFlat())
                switch (feature) {
                case T_L_LEMMA:
                    if (t>0) sample.put(feature, ECCommon.getPartial(lemmas[t-1]));
                    break;
                case T_L_POS:
                    if (t>0) sample.put(feature, Arrays.asList(poses[t-1]));
                    break;
                case T_R_LEMMA:
                    if (t<tokens.length) sample.put(feature, ECCommon.getPartial(lemmas[t]));
                    break;
                case T_R_POS:
                    if (t<tokens.length) sample.put(feature, Arrays.asList(poses[t]));
                    break;
                case T_R_PARSE_FRAME:
                    if (t<tokens.length) {
                        TBNode ancestor = tokens[t];
                        while (!ancestor.getPOS().matches("(IP|FRAG)") && ancestor.getParent()!=null && ancestor.getParent().getTokenSet().nextSetBit(0)==t)
                            ancestor = ancestor.getParent();
                        if (ancestor.getPOS().matches("(IP|FRAG)")) {
                            StringBuilder pathStr = new StringBuilder(ancestor.getPOS());
                            for (int c=0; c<ancestor.getChildren().length; ++c) {
                                if (c>0 && ancestor.getChildren()[c].getPOS().equals(ancestor.getChildren()[c-1].getPOS()))
                                    continue;
                                pathStr.append("-"+ancestor.getChildren()[c].getPOS());
                                
                                if (ancestor.getHead()==ancestor.getChildren()[c].getHead()) break;
                            }
                            sample.put(feature, Arrays.asList(pathStr.toString()));
                        } else if (ancestor.getParent()!=null) {
                            StringBuilder pathStr = new StringBuilder(ancestor.getParent().getPOS());
                            for (int c=0; c<ancestor.getParent().getChildren().length; ++c) {
                                if (c==ancestor.getChildIndex())
                                    pathStr.append("-ec");
                                else if (c>0 && ancestor.getParent().getChildren()[c].getPOS().equals(ancestor.getParent().getChildren()[c-1].getPOS()))
                                    continue;
                                pathStr.append("-"+ancestor.getParent().getChildren()[c].getPOS());
                                
                                if (ancestor.getParent().getChildren()[c].getHead()==ancestor.getParent().getHead()) break;
                            }
                            if (pathStr.toString().indexOf("-VP")>0)
                                sample.put(feature, Arrays.asList(pathStr.toString(), ancestor.getParent().getPOS()+"-ec-"+ancestor.getPOS()+"-2"));                            
                        }
                    }
                    break;
                default:
                    break;
                }
            positionSamples.add(sample);
        }
        
        // dependency & other head+token features
        for (int h=0; h<tokens.length; ++h) {
            PBInstance prop = props[h];
            TBNode head = tokens[h];
            
            TBNode headIP=null;
            TBNode headVP= null;
            int headVPChildIdx=0;
            int headIPChildIdx=0;
            
            if (chLangUtil.isVerb(head.getPOS())) {
            	TBNode headConstituent = head.getConstituentByHead();
            	TBNode node = head;
            	while (node!=headConstituent) {
            		if (node.getParent().getPOS().equals("IP")) {
            			headIP = node.getParent();
            			headIPChildIdx = node.getChildIndex();
            			break;
            		}
            		if ((headVP==null || node.getPOS().equals("VP")) && node.getParent().getPOS().equals("VP")) {
            			headVP = node.getParent();
            			headVPChildIdx = node.getChildIndex();
            		}
            		node = node.getParent();
            	}
            }
            
            for (int t=headMasks[h].nextSetBit(0); t>=0; t=headMasks[h].nextSetBit(t+1)) {
                TBNode token = t<tokens.length?tokens[t]:null;
                EnumMap<Feature,Collection<String>> sample = new EnumMap<Feature,Collection<String>>(Feature.class);
                sample.putAll(headSamples.get(h));
                sample.putAll(positionSamples.get(t));

                int childIPIdx=-1;
                int childVPIdx=-1;

                if (t<=h && headIP!=null && tokens[t].isDecendentOf(headIP) && 
                		(t==0 || !tokens[t-1].isDecendentOf(headIP) || headIP==tokens[t-1].getLowestCommonAncestor(tokens[t]))) {                    
                	TBNode node = tokens[t];
                	while (node.getParent()!=headIP)
                		node = node.getParent();
                	childIPIdx = node.getChildIndex();
                }
                
                if (t>h & headVP!=null && tokens[t-1].isDecendentOf(headVP) && 
                		(t==tokens.length || !tokens[t].isDecendentOf(headVP) || headVP==tokens[t-1].getLowestCommonAncestor(tokens[t]))) {
                	TBNode node = tokens[t-1];
                	while (node.getParent()!=headVP)
                		node = node.getParent();
                	childIPIdx = node.getChildIndex();
                	
                }
                
                for (Feature feature:features.getFeaturesFlat())
                    switch (feature) {
                    case D_POSITION:
                        sample.put(feature, Arrays.asList(t<=h?"left":"right"));
                        break;
                    case D_DIST:
                        sample.put(feature, Arrays.asList(Integer.toString(t-h)));
                        break;
                    case D_SPATH:
                    	/*
                        if (t<=h && t>0)                       	
                            sample.put(feature, Arrays.asList(makePath(tokens[t-1].getPath(tokens[h]), false)));
                        else if (t>h && t<tokens.length)
                            sample.put(feature, Arrays.asList(makePath(tokens[t].getPath(tokens[h]), false)));*/
                    	if (token!=null && token!=head) {
                    		TBNode node=token;
                    		//while (node.getParent()!=null && !node.getTokenSet().get(h))
                    		//	node=node.getParent();
                    		sample.put(feature, Arrays.asList(makePath(node.getPath(head), true)));
                    	}
                    	break;
                    case D_SFRONTIER:
                    	if (t>0 && token!=null) {
                    		TBNode topNode = tokens[t-1];
                    		topNode = topNode.getLowestCommonAncestor(token);
                    		TBNode lcaNode = token.getLowestCommonAncestor(head);
                    		sample.put(feature, Arrays.asList(makePath(lcaNode.getPath(topNode), true)));
                    	}
                    	break;
                    case D_DPATH:
                    	if (t<=h && t>0)
                            sample.put(feature, Arrays.asList(makeDepPath(tokens[t-1].getDepPath(tokens[h]), false)));
                        else if (t>h && t<tokens.length)
                            sample.put(feature, Arrays.asList(makeDepPath(tokens[t].getDepPath(tokens[h]), false)));
                        break;
                    case D_V_DIST:
                    {
                    	int count=0;
                        for (int i=(t<h?t:h)+1;i<(t<h?h:t); ++i)
                        	if (chLangUtil.isVerb(tokens[i].getPOS()))
                        		++count;
                        sample.put(feature, Arrays.asList(Boolean.toString(count==0)));
                    	break;
                    }
                    case D_COMMA_DIST:
                    {
                    	int count=0;
                        for (int i=(t<h?t:h)+1;i<(t<h?h:t); ++i)
                        	if (tokens[i].getWord().matches("，|,"))
                        		++count;
                        sample.put(feature, Arrays.asList(Boolean.toString(count==0)));
                    	break;
                    }
                    /*
                    case D_SRL_ARG01_SAME_SIDE:
                    	if (prop!= null) {
                            for (PBArg arg:prop.getArgs())
                            	if (arg.getTokenSet().nextSetBit(0)<h && ) {
                            	
                    	break;*/
                    case D_SRL_INFRONTARG:
                        if (prop!= null) {
                            for (PBArg arg:prop.getArgs())
                                if (arg.getTokenSet().nextSetBit(0)==t) {
                                    sample.put(feature, Arrays.asList(arg.getLabel(), Boolean.toString(true)));
                                    break;
                                }
                        }
                        break;
                    case D_SRL_BEHINDARG:
                        if (prop!= null) {
                            for (PBArg arg:prop.getArgs())
                                if (arg.getTokenSet().length()==t) {
                                    sample.put(feature, Arrays.asList(arg.getLabel(), Boolean.toString(true)));
                                    break;
                                }
                        }
                        break;
                    case D_SRL_INARG:              // positioned inside an argument of the predicate
                        if (prop!= null) {
                            for (PBArg arg:prop.getArgs())
                                if (t>0 && arg.getTokenSet().get(t-1) && arg.getTokenSet().get(t)) {
                                    sample.put(feature, Arrays.asList(Boolean.toString(true)));
                                    break;
                                }
                        }
                        break;
                    case D_IP_ISCHILD:  // for position left of verb, if the common ancestor has an IP
                    	if (t<=h && childIPIdx>=0)
                    		sample.put(feature, Arrays.asList(Boolean.TRUE.toString()));
                    	break;
                    case D_IP_LEFTSIB:  // POS of the left sibling under IP
                    	if (t<=h && childIPIdx>0)
                    		sample.put(feature, Arrays.asList(headIP.getChildren()[childIPIdx-1].getPOS()));
                    	break;
                    case D_IP_RIGHTSIB: // POS of right sibling under IP
                    	if (t<=h && childIPIdx>=0 && childIPIdx<headIP.getChildren().length)
                    		sample.put(feature, Arrays.asList(headIP.getChildren()[childIPIdx].getPOS()));
                    	break;
                    case D_IP_INCP:     // if IP is in CP (for both left & right)
                    	if (headIP!=null && headIP.getParent()!=null && headIP.getParent().getPOS().equals("CP"))
                    		sample.put(feature, Arrays.asList("IP_UNDER_CP"));
                    	break;
                    case D_VP_ISCHILD:  // for position right of verb, if the common ancestor has an VP
                    	if (t>h && childVPIdx>=0)
                    		sample.put(feature, Arrays.asList(Boolean.TRUE.toString()));
                    	break;
                    case D_VP_LEFTSIB:  // POS of the left sibling under VP
                    	if (t>h && childVPIdx>0)
                    		sample.put(feature, Arrays.asList(headVP.getChildren()[childVPIdx-1].getPOS()));
                    	break;
                    case D_VP_RIGHTSIB: // POS of right sibling under VP
                    	if (t>h && childVPIdx>=0 && childVPIdx<headVP.getChildren().length)
                    		sample.put(feature, Arrays.asList(headVP.getChildren()[childVPIdx].getPOS()));
                    	break;
                    default:
                        break;
                    }
                    
                    /*
                case SRL_ANCESTOR:      
                    List<String> before = new LinkedList<String>();
                    List<String> after = new LinkedList<String>();
                    
                    TBNode node = pred;
                    while (node.getParent()!=null) {
                        node = node.getParent();
                        if (node.getHead()!=pred) break;
                        before.add(node.getPOS());
                    }
                    after.add(node.getPOS());

                    if (node.getPOS().matches("LCP|CP")) {
                        if (node.getPOS().equals("CP") && node.getParent()!=null) {
                            node = node.getParent();
                            after.add(node.getPOS());
                        }
                        if (node.getParent()!=null) {
                            node = node.getParent();
                            after.add(node.getPOS());
                        }
                    } 
                    
                    sample.put(feature, Arrays.asList(before.toString(), "a-"+after.toString(), 
                            "al-"+after.get(after.size()-1), "al-"+after.get(after.size()-1)+"-"+node.getHeadword()));

                    break;
                case SRL_PARENTPRED: 
                    {
                        TBNode enclosingNode = null;
                        PBArg enclosingArg = null;
                        for (int e=0; e<predNodes.size(); ++e) {
                            if (p==e || props[e]==null) continue;
                            for (PBArg arg:props[e].getArgs())
                                if (arg.getTokenSet().get(pred.getTokenIndex())) {
                                    if (enclosingNode==null || enclosingNode.getLevelToRoot() < predNodes.get(e).getLevelToRoot()) {
                                        enclosingNode = predNodes.get(e);
                                        enclosingArg = arg;
                                    }
                                    break;
                                }
                        }
                        if (enclosingNode!=null)
                            sample.put(feature, Arrays.asList(Boolean.TRUE.toString(), enclosingNode.getWord(), enclosingArg.getLabel(), enclosingArg.getNode().getPOS()));
                    }
                    break;
                    */
                samples.add(sample);
            }
        }
        return samples;
    }

    EnumMap<Feature,Collection<String>> extractSequenceFeatures(TBTree tree, int headIdx, int tokenIdx, String[][] labels, boolean buildDictionary) {
        EnumMap<Feature,Collection<String>> featureMap = new EnumMap<Feature,Collection<String>>(Feature.class);
        
        Set<String> allLabelSet = new TreeSet<String>();
        for (int h=0; h<labels.length; ++h)
            for (int t=0; t<labels[h].length; ++t) {
                if (t==tokenIdx && h==headIdx) continue;
                if (labels[h][t]!=null && !ECCommon.NOT_EC.equals(labels[h][t]))
                    allLabelSet.add(labels[h][t]);
            }
        allLabelSet.add(ECCommon.NOT_EC);
        
        for (Feature feature:features.getFeaturesFlat()) {
            switch (feature) {
            case EC_LABEL:
                featureMap.put(feature, Arrays.asList(labels[headIdx][tokenIdx]==null?ECCommon.NOT_EC:labels[headIdx][tokenIdx]));
                break;              
            case ECP1:
                if (tokenIdx>0) {
                	if (buildDictionary) {
    	                if (!allLabelSet.isEmpty())
    	                    featureMap.put(feature, allLabelSet);
    	            } else {
	                    Set<String> labelSet = new TreeSet<String>();
	                    for (int h=0; h<labels.length; ++h)
	                        if (labels[h][tokenIdx-1]!=null && !ECCommon.NOT_EC.equals(labels[h][tokenIdx-1]))
	                            labelSet.add(labels[h][tokenIdx-1]);
	                    featureMap.put(feature, labelSet.isEmpty()?Arrays.asList(ECCommon.NOT_EC):labelSet);
    	            }
                }
                break;
            case ECN1:
                if (tokenIdx<tree.getTokenCount()) {
                	if (buildDictionary) {
    	                if (!allLabelSet.isEmpty())
    	                    featureMap.put(feature, allLabelSet);
    	            } else {
	                    Set<String> labelSet = new TreeSet<String>();
	                    for (int h=0; h<labels.length; ++h)
	                        if (labels[h][tokenIdx+1]!=null && !ECCommon.NOT_EC.equals(labels[h][tokenIdx+1]))
	                            labelSet.add(labels[h][tokenIdx+1]);
	                    featureMap.put(feature, labelSet.isEmpty()?Arrays.asList(ECCommon.NOT_EC):labelSet);
    	            }
                }
                break;
            case ECALL:
                if (!allLabelSet.isEmpty())
                    featureMap.put(feature, allLabelSet);
                break;
            case EC_TOKEN_LEFT:
                if (tokenIdx>0) {
                	if (buildDictionary) {
    	                if (!allLabelSet.isEmpty())
    	                    featureMap.put(feature, allLabelSet);
    	            } else {
	                    Set<String> labelSet = new TreeSet<String>();
	                    for (int t=0; t<tokenIdx; ++t)
	                        if (labels[headIdx][t]!=null && !ECCommon.NOT_EC.equals(labels[headIdx][t]))
	                            labelSet.add(labels[headIdx][t]);
	                    if (!labelSet.isEmpty())
	                        featureMap.put(feature, labelSet);
    	            }
                }
                break;
            case EC_TOKEN_RIGHT:
                if (tokenIdx<tree.getTokenCount()) {
                	if (buildDictionary) {
    	                if (!allLabelSet.isEmpty())
    	                    featureMap.put(feature, allLabelSet);
    	            } else {
	                    Set<String> labelSet = new TreeSet<String>();
	                    for (int t=tokenIdx+1; t<=tree.getTokenCount(); ++t)
	                        if (labels[headIdx][t]!=null && !ECCommon.NOT_EC.equals(labels[headIdx][t]))
	                            labelSet.add(labels[headIdx][t]);
	                    if (!labelSet.isEmpty())
	                        featureMap.put(feature, labelSet);
    	            }
                }
                break;
            case EC_TOKEN_SAMESIDE:
            	if (buildDictionary) {
	                if (!allLabelSet.isEmpty())
	                    featureMap.put(feature, allLabelSet);
	            } else {
	            	 Set<String> labelSet = new TreeSet<String>();
	            	 if (tokenIdx<=headIdx) {
	            		 for (int t=0; t<=headIdx; ++t)
	            			 if (t!=tokenIdx && labels[headIdx][t]!=null && !ECCommon.NOT_EC.equals(labels[headIdx][t]))
	            				 labelSet.add(labels[headIdx][t]);
	            	 } else {
	            		 for (int t=headIdx+1; t<=tree.getTokenCount(); ++t)
	            			 if (t!=tokenIdx && labels[headIdx][t]!=null && !ECCommon.NOT_EC.equals(labels[headIdx][t]))
	            				 labelSet.add(labels[headIdx][t]);
	            	 }
	            	 if (!labelSet.isEmpty())
	                        featureMap.put(feature, labelSet);
	            }
            	break;
            case EC_TOKEN_ALL:
            	if (buildDictionary) {
	                if (!allLabelSet.isEmpty())
	                    featureMap.put(feature, allLabelSet);
	            } else {
	                Set<String> labelSet = new TreeSet<String>();
	                for (int t=0; t<=tree.getTokenCount(); ++t) {
	                    if (t==tokenIdx) continue;
	                    if (labels[headIdx][t]!=null && !ECCommon.NOT_EC.equals(labels[headIdx][t]))
	                        labelSet.add(labels[headIdx][t]);
	                }
	                if (!labelSet.isEmpty())
	                    featureMap.put(feature, labelSet);
	            }
                break;
            case EC_CP_CHILD:
            	if (tree.getRootNode().getNodeByTokenIndex(headIdx).getConstituentByHead().getPOS().equals("CP")) {
            		int childHeadIdx = tree.getRootNode().getNodeByTokenIndex(headIdx).getConstituentByHead().getChildren()[0].getHead().getTokenIndex();
            		if (buildDictionary) {
                        if (!allLabelSet.isEmpty())
                            featureMap.put(feature, allLabelSet);
                    } else {
                    	Set<String> labelSet = new TreeSet<String>();
                        for (int t=0; t<=tree.getTokenCount(); ++t) {
                            if (t==tokenIdx) continue;
                            if (labels[childHeadIdx][t]!=null && !ECCommon.NOT_EC.equals(labels[childHeadIdx][t]))
                                labelSet.add(labels[childHeadIdx][t]);
                        }
                        if (!labelSet.isEmpty())
                            featureMap.put(feature, labelSet);
                    }
            	}
                break;
            case EC_HEAD_PARENT:
                if (buildDictionary) {
                    if (!allLabelSet.isEmpty())
                        featureMap.put(feature, allLabelSet);
                } else {
                    Set<String> labelSet = new TreeSet<String>();
                    TBNode headParent = tree.getNodeByTokenIndex(headIdx).getHeadOfHead();
                    if (headParent!=null)
                        for (int t=0; t<=tree.getTokenCount(); ++t)
                            if (labels[headParent.getTokenIndex()][t]!=null && !ECCommon.NOT_EC.equals(labels[headParent.getTokenIndex()][t]))
                                labelSet.add(labels[headParent.getTokenIndex()][t]);
                    featureMap.put(feature, labelSet.isEmpty()?Arrays.asList(ECCommon.NOT_EC):labelSet);
                }
                break;
            case EC_HEAD_ALL:
                if (buildDictionary) {
                    if (!allLabelSet.isEmpty())
                        featureMap.put(feature, allLabelSet);
                } else {
                    Set<String> labelSet = new TreeSet<String>();
                    for (int h=0; h<labels.length; ++h) {
                        if (h==headIdx) continue;
                        if (labels[h][tokenIdx]!=null && !ECCommon.NOT_EC.equals(labels[h][tokenIdx]))
                            labelSet.add(labels[h][tokenIdx]);
                    }
                    featureMap.put(feature, labelSet.isEmpty()?Arrays.asList(ECCommon.NOT_EC):labelSet);
                }
                break;
            default:
                break;
            }
        }
        return featureMap;
    }

    @Override
	public void addTrainingSentence(TBTree goldTree, TBTree parsedTree, List<PBInstance> props, boolean buildDictionary) {
        parsedTree = parsedTree==null?goldTree:parsedTree;
        
        BitSet[] headCandidates = ECCommon.getECCandidates(parsedTree);
        
        if (buildDictionary) {
        
	        thCount += parsedTree.getTokenCount();
	        BitSet headAll = new BitSet();
	        
	        for (int i=0; i<headCandidates.length; ++i)
	        	if (headCandidates[i].cardinality()>0)
	        		headAll.set(i);
	        /*
	        for (TBNode node:parsedTree.getTokenNodes()) {
	        	if (node.getPOS().startsWith("V")) {
	        		headAll.set(node.getTokenIndex());
	        		continue;
	        	}
	        	TBNode headConst = 	node.getConstituentByHead();
	        	TBNode constituent = node;
	        	while (constituent!=headConst) {
	        		if (constituent.getPOS().matches("[CIQ]P"))
	        			break;
	        		constituent = constituent.getParent();
	        	}
	        	if (constituent.getPOS().matches("[CIQ]P"))
	        		headAll.set(node.getTokenIndex());
	        }*/
	         
	        
	        /*
	        for (TBNode node:goldTree.getTerminalNodes()) {
	            if (node.isToken())
	                continue;
	            //++tCount;
	            TBNode head = node.getHeadOfHead();
	            if (head==null)
	            	continue;

	            TBNode parsedHead = parsedTree.getNodeByTokenIndex(head.getTokenIndex());
	            
	            if (parsedHead!=null && !parsedHead.getPOS().startsWith("V")) {
	            	TBNode headConst = 	parsedHead.getConstituentByHead();
		            TBNode lca = parsedTree.getNodeByTokenIndex(node.getTerminalIndex()<head.getTerminalIndex()?-node.getTokenIndex()-1:-node.getTokenIndex());
		            lca = parsedHead.getLowestCommonAncestor(lca);
		            
		            if (headConst == headConst.getLowestCommonAncestor(lca)) {
			            while(lca!=headConst) {
		        			if (lca.getPOS().matches("[CIQ]P"))
		            			break;
		        			lca = lca.getParent();
		        		}
		        		if (lca.getPOS().matches("[CIQ]P")) {
		        			headAll.set(parsedHead.getTokenIndex());
		        			break;
		        		}
		            }

	            	//System.out.println(head.getConstituentByHead().toPrettyParse(0));
	            	//System.out.println(parsedHead.getConstituentByHead().toPrettyParse(0));
	            	//System.out.printf("%d/%d\n",++count,tCount);
	            } else if (parsedHead!=null)
	            	headAll.set(parsedHead.getTokenIndex());
	        }*/
	        
	        
	        hCount+=headAll.cardinality();
	        
	        for (TBNode node:goldTree.getTerminalNodes()) {
	            if (node.isToken())
	                continue;
	            TBNode head = node.getHeadOfHead();
	            if (head==null || head.isEC())
	            	continue;
	            if (headAll.get(head.getTokenIndex())) count++;
	            tCount++;   
	        }
	        //System.out.printf("%d/%d %d/%d\n",count,tCount, hCount, thCount);
        }
        
        
        // TODO: test whether this is better?
        //ECCommon.addGoldCandidates(goldTree, headCandidates);
        
        for (TBNode node:goldTree.getTerminalNodes()) {
            if (node.isToken())
                continue;
            //++tCount;
            TBNode head = node.getHeadOfHead();
            if (head==null)
            	continue;

            TBNode parsedHead = parsedTree.getNodeByTokenIndex(head.getTokenIndex());
            /*
            if (parsedHead!=null && !parsedHead.getPOS().startsWith("V")) {
            	TBNode headConst = 	parsedHead.getConstituentByHead();
	            TBNode lca = parsedTree.getNodeByTokenIndex(node.getTerminalIndex()<head.getTerminalIndex()?-node.getTokenIndex():-node.getTokenIndex()-1);
	            lca = parsedHead.getLowestCommonAncestor(lca);
	            
	            if (headConst == headConst.getLowestCommonAncestor(lca)) {
		            while(lca!=headConst) {
	        			if (lca.getPOS().matches("[CIQ]P"))
	            			break;
	        			lca = lca.getParent();
	        		}
	        		if (lca.getPOS().matches("[CIQ]P"))
	        			break;
	            }

            	//System.out.println(head.getConstituentByHead().toPrettyParse(0));
            	//System.out.println(parsedHead.getConstituentByHead().toPrettyParse(0));
            	System.out.printf("%d/%d\n",++count,tCount);
            }*/
	            
            
            if (head!=null && head.isToken() && !head.getPOS().startsWith("V")) {
            	TBNode headConst = 	head.getConstituentByHead();
            	TBNode lca = head.getLowestCommonAncestor(node);

        		while(lca!=headConst) {
        			if (lca.getPOS().matches("[CIQ]P"))
            			break;
        			lca = lca.getParent();
        		}
        		if (lca.getPOS().matches("[CIQ]P"))
        			break;

            	//System.out.println(head.getConstituentByHead().toPrettyParse(0));
            	//System.out.println(parsedHead.getConstituentByHead().toPrettyParse(0));
        		//System.out.printf("%d/%d\n",++count,tCount);
            }
        }
        
        
        String[] labels = ECCommon.makeECDepLabels(goldTree, headCandidates);
        
        Comparator<String> labelComp = new Comparator<String>() {
			@Override
            public int compare(String lhs, String rhs) {
				if (lhs.equals("*OP*")) 
					return rhs.equals("*OP*")?0:1;
				if (rhs.equals("*OP*")) 
					return -1;
				if (lhs.equals("*T*"))
					return rhs.equals("*T*")?0:-1;
				if (rhs.equals("*T*"))
					return 1;
				if (lhs.equals("*pro*"))
					return rhs.equals("*pro*")?0:-1;
				if (rhs.equals("*pro*"))
					return 1;	
	            return String.CASE_INSENSITIVE_ORDER.compare(lhs, rhs);
            }
        };
        
        for (int l=0; l<labels.length; ++l) {
        	String[] subLabels = labels[l].trim().split("\\s+");
        	if (subLabels.length>1)
        		Arrays.sort(subLabels, labelComp);
        	//labels[l] = subLabels[0].equals("*OP*")?ECCommon.NOT_EC:subLabels[0];
        	labels[l] = subLabels[0];
        	if (subLabels.length>1 && !subLabels[1].equals(ECCommon.NOT_EC))
        		System.out.println(goldTree.getFilename()+':'+goldTree.getIndex()+' '+Arrays.asList(subLabels));
        }
        
        List<EnumMap<Feature,Collection<String>>> samples = extractSampleFeature(parsedTree==null?goldTree:parsedTree, headCandidates, props, buildDictionary);
        
        if (buildDictionary) {
            int i=0;
            String[][] labelMatrix = ECDepTreeSample.makeDepLabels(headCandidates, labels);
            for (int h=0; h<headCandidates.length; ++h)
                for (int t=headCandidates[h].nextSetBit(0); t>=0; t=headCandidates[h].nextSetBit(t+1)) {
                    boolean notEC = ECCommon.NOT_EC.equals(labels[i]);
                    features.addToDictionary(samples.get(i), notEC?notECFeatureWeight:1f);
                    features.addToDictionary(extractSequenceFeatures(parsedTree, h, t, labelMatrix, true), notEC?notECFeatureWeight:1f);
                    labelStringMap.adjustOrPutValue(labels[i], 1, 1);
                    ++i;
                }
        } else {  
            List<ECSample> sampleList = new ArrayList<ECSample>();
            
            int i=0;
            for (int h=0; h<headCandidates.length; ++h)
                for (int t=headCandidates[h].nextSetBit(0); t>=0; t=headCandidates[h].nextSetBit(t+1)) {
                    if (labelStringMap.containsKey(labels[i]))
                        sampleList.add(new ECSample(samples.get(i), labels[i]));
                    else
                        headCandidates[h].clear(t);
                    ++i;
                }
            
            if (!sampleList.isEmpty()) {
                List<ECTreeSample> tSamples = trainingSamples.get(parsedTree.getFilename());
                if (tSamples == null) {
                    tSamples = new ArrayList<ECTreeSample>();
                    trainingSamples.put(parsedTree.getFilename(), tSamples);
                }
                tSamples.add(new ECDepTreeSample(goldTree, parsedTree, sampleList.toArray(new ECSample[sampleList.size()]), headCandidates));
            }
        }
    }
    
    String[] train(Classifier classifier, int folds, int threads, String[] labels) {            
        int[][] X = null;
        int[] y = null;
        int[] seed = null;
        
        List<int[]> xList = new ArrayList<int[]>();
        TIntList yList = new TIntArrayList();
        TIntList seedList = new TIntArrayList();
        int lCnt = 0;
        
        
        int treeCnt = 0;
        String[] labelc = labels==null?null:new String[labels.length];
        for (Map.Entry<String, List<ECTreeSample>> entry:trainingSamples.entrySet()) {
            for (ECTreeSample treeSample:entry.getValue()) {
                ECSample[] samples = treeSample.getECSamples();

                if (labels!=null) {
                    ECDepTreeSample depSample = (ECDepTreeSample)treeSample;
                    BitSet[] headMasks = depSample.headMasks;
                    String[][] labelMatrix = ECDepTreeSample.makeDepLabels(headMasks, Arrays.copyOfRange(labels, lCnt, lCnt+samples.length));
                    
                    int i=0;
                    for (int h=0; h<headMasks.length; ++h)
                        for (int t=headMasks[h].nextSetBit(0); t>=0; t=headMasks[h].nextSetBit(t+1)) {
                            samples[i] = new ECSample(new EnumMap<Feature, Collection<String>>(samples[i].features), samples[i].label);
                            samples[i].features.putAll(extractSequenceFeatures(treeSample.parsedTree, h, t, labelMatrix, false));
                            ++i;
                        }
                }
                for (int i=0;i<samples.length; ++i) {
                    xList.add(features.getFeatureVector(samples[i].features));
                    yList.add(labelStringMap.get(samples[i].label));
                    seedList.add(treeCnt);
                }
                lCnt+=samples.length;
            }
            ++treeCnt;
        }
        X = xList.toArray(new int[xList.size()][]);
        y = yList.toArray();
        seed = seedList.toArray();

        int[] yV;
        if (folds>1) {
            CrossValidator validator = new CrossValidator(classifier, threads);
            yV =  validator.validate(folds, X, y, seed, true);
        } else {
            classifier.train(X, y);
            yV = new int[y.length];
            for (int i=0; i<y.length; ++i)
                yV[i] = classifier.predict(X[i]);
        }
        
        String[] newLabels = new String[yV.length];
        for (int i=0; i<yV.length; ++i)
            newLabels[i] = labelIndexMap.get(yV[i]);
        
        return newLabels;
    }
    
    // TODO: this is probably not the right algorithm
    void fillLinearLabel(TBNode head, String[][] predictions, String[] labels) {
        for (TBNode dep:head.getDependentNodes())
            fillLinearLabel(dep, predictions, labels);
        for (int i=0; i<predictions[head.getTokenIndex()].length; ++i)
            if (predictions[head.getTokenIndex()][i]!=null && !ECCommon.NOT_EC.equals(predictions[head.getTokenIndex()][i])) {
                if (labels[i]==null)
                    labels[i] = predictions[head.getTokenIndex()][i];
                else if (i<head.getTokenIndex())
                    labels[i] = predictions[head.getTokenIndex()][i]+' '+labels[i];
                else
                    labels[i] += ' '+predictions[head.getTokenIndex()][i];
            }
    }
    /*
    public String[] makeLinearLabel(TBTree tree, BitSet[] headMasks, String[] depLabels) {
        String[][] headPrediction = ECDepTreeSample.makeLabels(headMasks, depLabels);
        String[] linearLabels = new String[tree.getTokenCount()+1];
        
        fillLinearLabel(tree.getRootNode().getHead(), headPrediction, linearLabels);
        
        for (int l=0; l<linearLabels.length; ++l)
            if (linearLabels[l]==null)
                linearLabels[l] = ECCommon.NOT_EC;
            else
                linearLabels[l] = linearLabels[l].trim();
        return linearLabels;
    }*/
    
    public static String[] makeLinearLabel(TBTree tree, String[][] depLabels) {
       
        @SuppressWarnings("unchecked")
        List<DepLabel>[] depLabelList =new List[tree.getTokenCount()+1];
        TBNode[] tokens = tree.getTokenNodes();
        
        int i=0;
        for (int h=0;h<depLabels.length; ++h)
            for (int t=0; t<depLabels[h].length; ++t) {
                if (depLabels[h][t]!=null&&!ECCommon.NOT_EC.equals(depLabels[h][t])) {
                    if (depLabelList[t]==null)
                        depLabelList[t] = new ArrayList<DepLabel>();
                    depLabelList[t].add(new DepLabel(tokens[h], t<=h, depLabels[h][t]));
                }
                ++i;
            }
        
        String[] linearLabels = new String[tree.getTokenCount()+1];
        for (i=0; i<depLabelList.length; ++i)
            if (depLabelList[i]==null)
                linearLabels[i] = ECCommon.NOT_EC;
            else {
                Collections.sort(depLabelList[i]);
                for (DepLabel label:depLabelList[i])
                    linearLabels[i] = linearLabels[i]==null?label.label:linearLabels[i]+' '+label.label;
            }

        return linearLabels;
    }
    
    String[][] addOPLabels(TBTree tree, String[][] labels) {
    	for (TBNode head:tree.getTokenNodes()) {
    		if (head.getConstituentByHead().getPOS().equals("CP")) {
    			int h = head.getPOS().equals("DEC")?head.getConstituentByHead().getChildren()[0].getHead().getTokenIndex():head.getTokenIndex();
    			boolean found=false;
    			for (String label:labels[h])
    				if (label!=null && label.equals("*T*")) {
    					found = true;
    					break;
    				}	
    			if (found) {
    				int t = head.getConstituentByHead().getTokenSet().nextSetBit(0);
    				if (labels[head.getTokenIndex()][t]!=null && labels[head.getTokenIndex()][t].startsWith("*OP*"))
    					continue;
    				labels[head.getTokenIndex()][t] = labels[head.getTokenIndex()][t]==null||labels[head.getTokenIndex()][t].equals(ECCommon.NOT_EC)?"*OP*":"*OP* "+labels[head.getTokenIndex()][t];
    			}
    		}
    	}
    	return labels;
    }
    
    static class Prediction {
    	public Prediction(int labelNum) {
    		values = new double[labelNum];
    	}
    	
    	void makeLabel(TIntObjectMap<String> labelMap) {
    		double highVal = Double.MIN_VALUE;
    		int highIdx = 0;
    		for (int i=0; i<values.length; ++i)
    			if (values[i]>highVal) {
    				highVal = values[i];
    				highIdx = i;
    			}
    		label = labelMap.get(highIdx+1);
    	}
    	
    	String label;
    	double[] values;
    }
    
    
    void constrainSingleType(Prediction[] predictions, int lhs, int rhs, String type) {
    	
    	int typeIndex = labelStringMap.get(type)-1;
    	
    	double highVal = Double.MIN_VALUE;
		for (int t=lhs; t<rhs;++t) {
			if (predictions[t]==null) continue;
			if (type.equals(predictions[t].label) && predictions[t].values[typeIndex]>highVal)
				highVal = predictions[t].values[typeIndex];
		}
		if (highVal==Double.MIN_VALUE) 
			return;
		for (int t=lhs; t<rhs;++t) {
			if (predictions[t]==null) continue;
			if (predictions[t].values[typeIndex]<highVal) {
				predictions[t].values[typeIndex] = Double.MIN_VALUE;
				String oldLabel = predictions[t].label;
				predictions[t].makeLabel(labelIndexMap);
				if (!oldLabel.equals(predictions[t].label))
					System.out.printf("%s changed to %s\n", oldLabel, predictions[t].label);
				
			}
		}
    }
    
    String[][] applyConstraints(Prediction[][] predictions) {
    	String[][] labels = new String[predictions.length][predictions.length+1];
    	
    	for (int h=0; h<predictions.length; ++h) {    		
    		//constrainSingleType(predictions[h], 0, predictions[h].length, "*T*");
    		//constrainSingleType(predictions[h], 0, predictions[h].length, "*PRO*");
    		//constrainSingleType(predictions[h], 0, h+1, "*pro*");
    		//constrainSingleType(predictions[h], h+1, predictions[h].length, "*pro*");
    		
    		for (int t=0; t<predictions[h].length; ++t)
    			if (predictions[h][t]!=null)
    				labels[h][t]=predictions[h][t].label;
    	}
    	
    	return labels;
    }
    
    public String[][] predictDep(TBTree tree, List<PBInstance> props) {
    	 BitSet[] headMasks = ECCommon.getECCandidates(tree, false);

         List<EnumMap<Feature,Collection<String>>> samples = extractSampleFeature(tree, headMasks, props, false);
         
         Prediction[] prediction = new Prediction[samples.size()];
         for (int i=0; i<samples.size(); ++i) {
        	 prediction[i] = new Prediction(labelStringMap.size());
             prediction[i].label = labelIndexMap.get(classifier.predictValues(features.getFeatureVector(samples.get(i)), prediction[i].values));
         }
         
         if (!quickClassify && stage2Classifier!=null) {
             String[] linearLabels = new String[prediction.length];
             for (int i=0; i<linearLabels.length; ++i)
            	 linearLabels[i] = prediction[i].label;
             int i=0;
             String[][] labelMatrix = ECDepTreeSample.makeDepLabels(headMasks, linearLabels);
             for (int h=0; h<headMasks.length; ++h)
                 for (int t=headMasks[h].nextSetBit(0); t>=0; t=headMasks[h].nextSetBit(t+1)) {
                     samples.get(i).putAll(extractSequenceFeatures(tree, h, t, labelMatrix, false));
                     prediction[i].label = labelIndexMap.get(stage2Classifier.predictValues(features.getFeatureVector(samples.get(i)), prediction[i].values));
                     ++i;
                 }
         }         
         return addOPLabels(tree,applyConstraints(ECDepTreeSample.makeDepLabels(headMasks, new Prediction[headMasks.length][headMasks.length+1], prediction)));    
    }
    
    @Override
	public String[] predict(TBTree tree, List<PBInstance> props) {
    	return makeLinearLabel(tree, predictDep(tree, props));
    }
    
    @Override
	public void train(Properties prop) {
        System.out.printf("pro score %d/%d\n", propCnt, propTotal);
        System.out.printf("elipsis score %d/%d\n", elipsisCnt, elipsisTotal);
        int folds = Integer.parseInt(prop.getProperty("crossvalidation.folds","5"));
        int threads = Integer.parseInt(prop.getProperty("crossvalidation.threads","2"));

        //boolean hasSequenceFeature = true;
        boolean hasSequenceFeature = Feature.hasSequenceFeature(features.getFeaturesFlat());
        
        classifier = new LinearClassifier();
        classifier.initialize(labelStringMap, prop);
        
        String[] goldLabels = null;

        List<String> labelList = new ArrayList<String>();
        for (Map.Entry<String, List<ECTreeSample>> entry:trainingSamples.entrySet())
            for (ECTreeSample treeSample:entry.getValue())
                for (ECSample sample:treeSample.getECSamples())
                    labelList.add(sample.label);
        goldLabels = labelList.toArray(new String[labelList.size()]);
        
        String[] newLabels = train(classifier, hasSequenceFeature?folds:1, threads, null);
        {
            ECScore scorePlain = new ECScore(new TreeSet<String>(Arrays.asList(labelStringMap.keys(new String[labelStringMap.size()]))));
            for (int i=0; i<newLabels.length; ++i)
                scorePlain.addResult(newLabels[i], goldLabels[i]);
            System.out.println("Plain:");
            System.out.println(scorePlain.toString());
            
            ECScore scoreStructured = new ECScore(new TreeSet<String>(Arrays.asList(labelStringMap.keys(new String[labelStringMap.size()]))));
            int c=0;
            for (Map.Entry<String, List<ECTreeSample>> entry:trainingSamples.entrySet())
                for (ECTreeSample treeSample:entry.getValue()) {
                    String[] gLabels = ECCommon.getECLabels(treeSample.goldTree, labelType);
                    String[] sLabels = makeLinearLabel(treeSample.parsedTree, ECDepTreeSample.makeDepLabels(((ECDepTreeSample)treeSample).headMasks, Arrays.copyOfRange(newLabels, c, c+=treeSample.samples.length)));
                    for (int i=0; i<gLabels.length; ++i)
                        scoreStructured.addResult(sLabels[i]==null?ECCommon.NOT_EC:sLabels[i].trim(), gLabels[i]);
                }
            System.out.println("Structured:");
            System.out.println(scoreStructured.toString());
        }
        
        if (hasSequenceFeature) {
            stage2Classifier = new LinearClassifier();
            stage2Classifier.initialize(labelStringMap, prop);
            
            newLabels = train(stage2Classifier, 1, threads, newLabels);
            ECScore scorePlain = new ECScore(new TreeSet<String>(Arrays.asList(labelStringMap.keys(new String[labelStringMap.size()]))));
            for (int i=0; i<newLabels.length; ++i)
                scorePlain.addResult(newLabels[i], goldLabels[i]);
            System.out.println("Plain:");
            System.out.println(scorePlain.toString());
            
            ECScore scoreStructured = new ECScore(new TreeSet<String>(Arrays.asList(labelStringMap.keys(new String[labelStringMap.size()]))));
            int c=0;
            for (Map.Entry<String, List<ECTreeSample>> entry:trainingSamples.entrySet())
                for (ECTreeSample treeSample:entry.getValue()) {
                    String[] gLabels = ECCommon.getECLabels(treeSample.goldTree, labelType);
                    String[] sLabels = makeLinearLabel(treeSample.parsedTree, ECDepTreeSample.makeDepLabels(((ECDepTreeSample)treeSample).headMasks, Arrays.copyOfRange(newLabels, c, c+=treeSample.samples.length)));
                    for (int i=0; i<gLabels.length; ++i)
                        scoreStructured.addResult(sLabels[i]==null?ECCommon.NOT_EC:sLabels[i].trim(), gLabels[i]);
                }
            System.out.println("Structured:");
            System.out.println(scoreStructured.toString());
        }
            
        
        /*
        int rounds = hasSequenceFeature?5:1;
        double threshold = 0.001;
        
        String[] goldLabels = null;

        List<String> labelList = new ArrayList<String>();
        for (Map.Entry<String, List<ECTreeSample>> entry:trainingSamples.entrySet())
            for (ECTreeSample treeSample:entry.getValue())
                for (ECSample sample:treeSample.getECSamples())
                    labelList.add(sample.label);
        goldLabels = labelList.toArray(new String[labelList.size()]);

        String[] labels = goldLabels;
        for (int r=0; r<rounds; ++r) {
            String[] newLabels = train(hasSequenceFeature?folds:1, threads, labels);
            int cnt=0;
            for (int i=0; i<labels.length; ++i) {
                //if (i<50) System.out.println(i+" "+labels[i]+" "+newLabels[i]);
                if (labels[i].equals(newLabels[i])) ++cnt;
            }
            double agreement = cnt*1.0/labels.length;
            System.out.printf("Round %d: %f\n", r, agreement);
            labels = newLabels;
        
            ECScore score = new ECScore(new TreeSet<String>(Arrays.asList(labelStringMap.keys(new String[labelStringMap.size()]))));
            for (int i=0; i<labels.length; ++i)
                score.addResult(labels[i], goldLabels[i]);
        
            System.out.println(score.toString());
            
            if (1-agreement<=threshold) break;
        }*/
    }

}
