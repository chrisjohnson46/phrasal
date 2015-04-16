package edu.stanford.nlp.mt.util;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.LineNumberReader;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.PrintStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

import com.esotericsoftware.kryo.Kryo;
import com.esotericsoftware.kryo.io.Input;
import com.esotericsoftware.kryo.io.Output;
import com.esotericsoftware.kryo.io.UnsafeInput;
import com.esotericsoftware.kryo.io.UnsafeOutput;

import edu.stanford.nlp.mt.tm.CompiledPhraseTable;
import edu.stanford.nlp.stats.Counter;
import edu.stanford.nlp.stats.Counters;
import edu.stanford.nlp.util.Index;

/**
 * Various static methods for reading and writing files. Also includes
 * utilities for converting string-valued objects to other types after
 * reading from file.
 *
 * @author danielcer
 * @author Spence Green
 *
 */
public final class IOTools {

  // TODO(spenceg): Should be user-configurable via various main methods (Phrasal,
  // OnlineTuner, PhraseExtract, etc.)
  public static final String DEFAULT_ENCODING = "UTF-8";

  public static final String WEIGHTS_FILE_EXTENSION = ".binwts";
  public static final String GZ_EXTENSION = ".gz";
  public static final String BIN_EXTENSION = ".bin";
  public static final String GZ_BIN_EXTENSION = BIN_EXTENSION + GZ_EXTENSION;

  private IOTools() {}

  /**
   * Converts a string list of scores to float.
   *
   * @throws NumberFormatException
   */
  public static float[] stringListToNumeric(List<String> scoreList) throws NumberFormatException {
    float[] scores = new float[scoreList.size()];
    int scoreId = 0;
    for (String score : scoreList) {
      float floatScore = (float) Double.parseDouble(score);
      if (Float.isNaN(floatScore)) {
        throw new NumberFormatException("Unparseable number: " + score);
      }
      scores[scoreId++] = floatScore;
    }
    return scores;
  }

  public static LineNumberReader getReaderFromFile(File fileName) {
    return getReaderFromFile(fileName.getPath());
  }

  public static LineNumberReader getReaderFromFile(String fileName) {
    return getReaderFromFile(fileName, DEFAULT_ENCODING);
  }

  public static LineNumberReader getReaderFromFile(String fileName, String encoding) {
    LineNumberReader reader; // = null;
    File f = new File(fileName);
    try {
      if (f.getAbsolutePath().endsWith(GZ_EXTENSION)) {
        reader = new LineNumberReader(new InputStreamReader(
            new GZIPInputStream(new FileInputStream(f), 8192), encoding));
      } else {
        reader = new LineNumberReader(new InputStreamReader(
            new BufferedInputStream(new FileInputStream(f)), encoding));
      }
    } catch (IOException e) {
      e.printStackTrace();
      throw new RuntimeException("Error reading: " + fileName);
    }
    return reader;
  }

  public static PrintStream getWriterFromFile(File fileName) {
    return getWriterFromFile(fileName.getPath());
  }

  public static PrintStream getWriterFromFile(String fileName) {
    return getWriterFromFile(fileName, DEFAULT_ENCODING);
  }

  public static PrintStream getWriterFromFile(String fileName, String encoding) {
    PrintStream output = null;
    try {
      if (fileName != null) {
        if (fileName.endsWith(GZ_EXTENSION)) {
          output = new PrintStream(new GZIPOutputStream(new BufferedOutputStream(new FileOutputStream(
              fileName))), false, encoding);
        } else {
          output = new PrintStream(new BufferedOutputStream(new FileOutputStream(fileName)), false,
              encoding);
        }
      }
    } catch (IOException e) {
      e.printStackTrace();
    }
    return output;
  }

  public static void addConfigFileProperties(Properties prop, String filename)
      throws IOException {
    LineNumberReader reader = new LineNumberReader(new FileReader(filename));
    String linePattern = "(\\S+)\\s+=\\s+(\\S+)";
    Pattern pattern = Pattern.compile(linePattern);
    for (String line; (line = reader.readLine()) != null;) {
      if (line.matches("^\\s*$"))
        continue;
      if (line.charAt(0) == '#')
        continue;
      line = line.replaceAll("#.*$", "");
      Matcher matcher = pattern.matcher(line);
      if (matcher.find()) {
        assert (matcher.groupCount() == 2);
        prop.setProperty(matcher.group(0), matcher.group(1));
      }
    }
    reader.close();
  }

  /**
   * Parse a Phrasal ini file.
   *
   * @param filename
   * @throws IOException
   */
  public static Map<String, List<String>> readConfigFile(String filename)
      throws IOException {
    Map<String, List<String>> config = new HashMap<>();
    LineNumberReader reader = getReaderFromFile(filename);
    for (String line; (line = reader.readLine()) != null;) {
      line = line.trim().replaceAll("#.*$", "");
      if (line.length() == 0)
        continue;
      if (line.charAt(0) != '[' || line.charAt(line.length() - 1) != ']') {
        reader.close();
        throw new RuntimeException(
            String
                .format(
                    "Expected bracketing of option name by '[',']', line: %d label: %s",
                    reader.getLineNumber(), line));
      }
      String nextArgLine = line;

      while (nextArgLine != null) {
        String key = line.substring(1, nextArgLine.length() - 1);
        nextArgLine = null;
        List<String> entries = new ArrayList<String>();
        while ((line = reader.readLine()) != null) {
          if (line.matches("^\\s*$"))
            break;
          if (line.startsWith("[")) {
            nextArgLine = line;
            break;
          }
          if (line.charAt(0) == '#')
            break;
          line = line.replaceAll("#.*$", "");
          String[] fields = line.split("\\s+");
          entries.addAll(Arrays.asList(fields));
        }
        if (!entries.isEmpty())
          config.put(key, entries);
      }
    }
    reader.close();
    return config;
  }

  /**
   * Deserialize an object.
   * 
   * @param filename
   * @return
   * @throws IOException
   * @throws ClassNotFoundException
   */
  public static <T> T deserialize(String filename, Class<T> type) throws IOException {
    try {
      T object;
      if (filename.endsWith(GZ_BIN_EXTENSION) || filename.endsWith(BIN_EXTENSION)) {
        Kryo kryo = new Kryo();
        Input input = new UnsafeInput(filename.endsWith(GZ_EXTENSION) ? 
            new GZIPInputStream(new FileInputStream(filename)) : new FileInputStream(filename));
        object = kryo.readObject(input, type);
        input.close();
        
      } else if (filename.endsWith(GZ_EXTENSION)) {
        FileInputStream input = new FileInputStream(new File(filename));
        ObjectInputStream inStream = new ObjectInputStream(new GZIPInputStream(input));
        object = type.cast(inStream.readObject());
        inStream.close();

      } else {
        FileInputStream input = new FileInputStream(new File(filename));
        ObjectInputStream inStream = new ObjectInputStream(input);
        object = type.cast(inStream.readObject());
        inStream.close();
      }

      return object;
      
    } catch (FileNotFoundException | ClassNotFoundException e) {
      throw new RuntimeException(e);
    }
  }
  
  /**
   * Serialize an object.
   * 
   * @param filename
   * @param o
   * @throws IOException
   */
  public static void serialize(String filename, Object o) throws IOException {
    if (filename.endsWith(GZ_BIN_EXTENSION) || filename.endsWith(BIN_EXTENSION)) {
      Kryo kryo = new Kryo();
      Output output = new UnsafeOutput(filename.endsWith(GZ_EXTENSION) ? 
          new GZIPOutputStream(new FileOutputStream(filename)) : new FileOutputStream(filename));
      kryo.writeObject(output, o);
      output.close();
      
    } else if (filename.endsWith(GZ_EXTENSION)) {
      FileOutputStream out = new FileOutputStream(new File(filename));
      ObjectOutputStream output = new ObjectOutputStream(new GZIPOutputStream(out));
      output.writeObject(o);
      output.close();
      
    } else {
      FileOutputStream out = new FileOutputStream(new File(filename));
      ObjectOutputStream output = new ObjectOutputStream(out);
      output.writeObject(o);
      output.close();
    }
  }
  
  /**
   * Read weights from a file. Supports both binary and text formats.
   *
   * @param filename
   * @param featureIndex
   * @return a counter of weights
   * @throws IOException
   * @throws ClassNotFoundException
   */
  @SuppressWarnings("unchecked")
  public static Counter<String> readWeights(String filename,
      Index<String> featureIndex) {
    Counter<String> wts;
    try {
      ObjectInputStream ois = new ObjectInputStream(new FileInputStream(
          filename));
      wts = (Counter<String>) ois.readObject();
      ois.close();
    
    } catch (IOException e) {
       wts = Counters.loadCounter(filename, String.class);
    } catch (ClassNotFoundException e) {
       wts = Counters.loadCounter(filename, String.class);
    }

    if (featureIndex != null) {
      for (String key : wts.keySet()) {
        featureIndex.addToIndex(key);
      }
    }
    return wts;
  }


  public static Counter<String> readWeights(String filename) {
    return readWeights(filename, null);
  }

  /**
   * Write weights to a file. Supports both binary and text formats.
   *
   * @param filename
   * @param wts
   */
  public static void writeWeights(String filename, Counter<String> wts) {
    try {
      if (filename.endsWith(WEIGHTS_FILE_EXTENSION)) {
        ObjectOutputStream oos = new ObjectOutputStream(new FileOutputStream(
            filename));
        oos.writeObject(wts);
        oos.close();
      } else {
        Counters.saveCounter(wts, filename);
      }
    } catch (FileNotFoundException e) {
      e.printStackTrace();
    } catch (IOException e) {
      e.printStackTrace();
    }
  }

  /**
   * Write an n-best list to file.
   *
   * @param translations
   * @param sourceInputId
   * @param nbestWordInternalAlignments
   */
  public static void writeNbest(List<RichTranslation<IString, String>> translations,
      int sourceInputId,
      String outputType,
      Pattern featurePattern,
      PrintStream nbestListWriter) {
    assert translations != null;
    assert nbestListWriter != null;

    StringBuilder sb = new StringBuilder(translations.size() * 500);
    String nl = System.getProperty("line.separator");
    for (RichTranslation<IString, String> translation : translations) {
      if (outputType.equals("moses")) {
        translation.nbestToMosesStringBuilder(sourceInputId, sb, featurePattern, false, false);
      } else if (outputType.equals("bolt")) {
        translation.nbestToMosesStringBuilder(sourceInputId, sb, featurePattern, true, false);
      } else if (outputType.equals("nnlm")) {
        translation.nbestToMosesStringBuilder(sourceInputId, sb, featurePattern, false, true);
      } else if (outputType.equals("nnlm-bolt")) {
        translation.nbestToMosesStringBuilder(sourceInputId, sb, featurePattern, true, true);
      } else {
        sb.append(sourceInputId).append(" ").append(CompiledPhraseTable.FIELD_DELIM).append(" ");
        sb.append(translation.toString());
      }
      sb.append(nl);
    }
    nbestListWriter.append(sb.toString());
  }

  
  /**
   * Write single best translations to file.
   *
   * @param translations
   * @param singleBestWriter
   */
  public static void writeSingleBest(Map<Integer,Sequence<IString>> translations,
      PrintStream singleBestWriter) {
    assert translations != null;
    assert singleBestWriter != null;
    String nl = System.getProperty("line.separator");
    
    for(int i = 0; i < translations.size(); ++i) {
      assert translations.containsKey(i);
      singleBestWriter.append(translations.get(i).toString() + nl);
    }
  }

  
  /**
   * Write an empty entry to a n-best list file.
   */
  public static void writeEmptyNBest(int sourceInputId, PrintStream nbestListWriter) {
    StringBuilder sb = new StringBuilder(50);
    String nl = System.getProperty("line.separator");
    sb.append(sourceInputId).append(" ").append(CompiledPhraseTable.FIELD_DELIM).append(" ");
    sb.append(" ").append(CompiledPhraseTable.FIELD_DELIM).append(" ");
    sb.append(" ").append(CompiledPhraseTable.FIELD_DELIM).append(" ");
    sb.append(" 0.0000E0 ").append(CompiledPhraseTable.FIELD_DELIM).append(" ");
    sb.append(" ");
    sb.append(nl);
    nbestListWriter.append(sb.toString());
  }

  /**
   * Return a list of files given a path prefix, e.g., passing the path
   *  /home/me/ref  will return all files in /home/me that begin with ref.
   */
  public static String[] fileNamesFromPathPrefix(String pathPrefix) {
    File p = new File(pathPrefix);
    final String filePrefix = p.getName();
    File folder = p.getParent() == null ? new File(".") : new File(p.getParent());
    if (folder.exists() && folder.isDirectory()) {
      String[] fileNames = folder.list((dir, name) -> name.startsWith(filePrefix));
      for (int i = 0; i < fileNames.length; ++i) {
        File path = new File(folder, fileNames[i]);
        fileNames[i] = path.getPath();
      }
      return fileNames;

    } else if (p.exists()) {
      return new String[] { p.getPath() };

    } else {
      return new String[0];
    }
  }
}
