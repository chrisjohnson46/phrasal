package edu.stanford.nlp.mt.tm;

import java.io.IOException;
import java.util.LinkedList;
import java.util.List;

import edu.stanford.nlp.mt.tm.DynamicTranslationModel.FeatureTemplate;
import edu.stanford.nlp.mt.util.IString;
import edu.stanford.nlp.util.Pair;

/**
 * Load a phrase table from a filename
 * 
 * @author Daniel Cer
 * @author Spence Green
 */
public class TranslationModelFactory {

  public static final String QUERY_LIMIT_OPTION = "querylimit";
  public static final String FEATURE_PREFIX_OPTION = "featpref";
  public static final String DYNAMIC_INDEX = "dyn-index";
  public static final String DYNAMIC_SAMPLE_SIZE = "dyn-sample";
  public static final String SEPARATOR = ":";
  
  public static final String DYNAMIC_TAG = "dyn:";
  public static final String DTU_TAG = "dtu:";
  
  /**
   * Factory method for phrase table loading.
   * 
   * @param options
   * @return
   * @throws IOException
   */
  @SuppressWarnings({ "unchecked", "rawtypes" })
  static public <FV> Pair<TranslationModel<IString,FV>,List<PhraseTable<IString>>> factory(
      String filename, String...options) throws IOException {
    
    // Parse options
    int queryLimit = -1;
    String featurePrefix = null;
    boolean setSystemIndex = false;
    int dynamicSampleSize = DynamicTranslationModel.DEFAULT_SAMPLE_SIZE;
    for (String option : options) {
      String[] fields = option.split(SEPARATOR);
      assert fields.length == 2 : String.format("Invalid option: " + option);
      String key = fields[0];
      String value = fields[1];
      if (key.equals(QUERY_LIMIT_OPTION)) {
        queryLimit = Integer.parseInt(value);
      } else if (key.equals(FEATURE_PREFIX_OPTION)) {
        featurePrefix = value;
      } else if (key.equals(DYNAMIC_INDEX)) {
        setSystemIndex = true;
      } else if (key.equals(DYNAMIC_SAMPLE_SIZE)) {
        dynamicSampleSize = Integer.valueOf(value);
      }
    }
    
    List<TranslationModel<IString,FV>> generators = new LinkedList<>();
    List<PhraseTable<IString>> tables = new LinkedList<>();

    TranslationModel<IString,FV> translationModel;
    if (filename.startsWith(DTU_TAG)) {
      String file = filename.substring(DTU_TAG.length());
      translationModel = new DTUTable<FV>(file);
    
    } else if (filename.startsWith(DYNAMIC_TAG)) {
      String file = filename.substring(DYNAMIC_TAG.length());
      translationModel = DynamicTranslationModel.load(file);
      ((DynamicTranslationModel) translationModel).initialize(setSystemIndex, dynamicSampleSize);
      ((DynamicTranslationModel) translationModel).setFeatureTemplate(FeatureTemplate.DENSE_EXT);
      
    } else {
      translationModel = featurePrefix == null ? new CompiledPhraseTable<FV>(filename) :
        new CompiledPhraseTable<FV>(featurePrefix, filename);
    }
    generators.add(translationModel);
    if (translationModel instanceof PhraseTable) {
      tables.add((PhraseTable<IString>) translationModel);
    }

    CombinedPhraseGenerator<IString,FV> gen = queryLimit == -1 ? 
        new CombinedPhraseGenerator<IString,FV>(generators) :
          new CombinedPhraseGenerator<IString,FV>(generators, queryLimit);
    
    Pair<TranslationModel<IString,FV>,List<PhraseTable<IString>>> pair =
        new Pair<TranslationModel<IString,FV>,List<PhraseTable<IString>>>(
          gen, tables);
    
    return pair;
  }
}
