package clearcommon.util;

import java.util.Collections;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

public class PBFrame {
	public class Roleset {
		String id;
		Set<String> roles;
		
		public Roleset(String id) {
			this.id = id;
			this.roles = new HashSet<String>();
		}
		
		public String getId() {
			return id;
		}

		public Set<String> getRoles() {
			return Collections.unmodifiableSet(roles);
		}
		
		public String toString() {
			return id+' '+roles;
		}
	}
	
	String predicate;
	LanguageUtil.POS pos;
	Map<String, Roleset> rolesets;

	public PBFrame(String predicate, LanguageUtil.POS pos) {
		this.predicate = predicate;
		this.pos = pos;
		rolesets = new TreeMap<String, Roleset>();
	}
	
	public String getPredicate() {
		return predicate;
	}
	
	public LanguageUtil.POS getPos() {
		return pos;
	}
	
	public Map<String, Roleset> getRolesets() {
		return Collections.unmodifiableMap(rolesets);
	}
	
	public void addRoleset(Roleset roleset) {
		rolesets.put(roleset.id, roleset);
	}
	
	public String toString() {
		StringBuilder builder = new StringBuilder();
		char pos = this.pos.equals(LanguageUtil.POS.NOUN)?'n':(this.pos.equals(LanguageUtil.POS.VERB)?'v':'j');
		
		builder.append(predicate+'-'+pos+'\n');
		for (Map.Entry<String, Roleset> entry:rolesets.entrySet())
			builder.append("  "+entry.getValue()+"\n");
		return builder.toString();
	}
	
}
