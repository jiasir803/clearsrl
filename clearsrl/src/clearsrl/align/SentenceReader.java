package clearsrl.align;

import java.io.FileNotFoundException;
import java.util.Properties;

public abstract class SentenceReader {
    Properties props;
	
    public SentenceReader(Properties props) {
        this.props = props;
    }
    
	public abstract void initialize() throws FileNotFoundException;
	public abstract Sentence nextSentence();
	public abstract void close();
}