///////////////////////////////////////////////////////////////////////////////
//  textdbutil.scala
//
//  Copyright (C) 2012 Ben Wing, The University of Texas at Austin
//
//  Licensed under the Apache License, Version 2.0 (the "License");
//  you may not use this file except in compliance with the License.
//  You may obtain a copy of the License at
//
//      http://www.apache.org/licenses/LICENSE-2.0
//
//  Unless required by applicable law or agreed to in writing, software
//  distributed under the License is distributed on an "AS IS" BASIS,
//  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
//  See the License for the specific language governing permissions and
//  limitations under the License.
///////////////////////////////////////////////////////////////////////////////

package opennlp.fieldspring.util

import collection.mutable
import util.control.Breaks._

import java.io.PrintStream

import printutil.{errprint, warning}
import ioutil._

package object textdbutil {
  /**
   * A line processor where each line is made up of a fixed number
   * of fields, separated by some sort of separator (by default a tab
   * character).  No implementation is provided for `process_lines`,
   * the driver function.  This function in general should loop over the
   * lines, calling `parse_row` on each one.
   *
   * @param split_re Regular expression used to split one field from another.
   *   By default a tab character.
   */
  trait FieldLineProcessor[T] extends LineProcessor[T] {
    val split_re: String = "\t"

    var all_num_processed = 0
    var all_num_bad = 0
    var num_processed = 0
    var num_bad = 0

    var fieldnames: Seq[String] = _

    /**
     * Set the field names used for processing rows.
     */
    def set_fieldnames(fieldnames: Seq[String]) {
      this.fieldnames = fieldnames
    }

    override def begin_process_file(filehand: FileHandler, file: String) {
      num_processed = 0
      num_bad = 0
      super.begin_process_file(filehand, file)
    }

    override def end_process_file(filehand: FileHandler, file: String) {
      all_num_processed += num_processed
      all_num_bad += num_bad
      super.end_process_file(filehand, file)
    }

    /**
     * Parse a given row into fields.  Call either #process_row or
     * #handle_bad_row.
     *
     * @param line Raw text of line describing the row
     * @return True if processing should continue, false if it should stop.
     */
    def parse_row(line: String) = {
      // println("[%s]" format line)
      val fieldvals = line.split(split_re, -1)
      if (fieldvals.length != fieldnames.length) {
        handle_bad_row(line, fieldvals)
        num_processed += 1
        true
      } else {
        val (good, keep_going) = process_row(fieldvals)
        if (!good)
          handle_bad_row(line, fieldvals)
        num_processed += 1
        keep_going
      }
    }

    /*********************** MUST BE IMPLEMENTED *************************/

    /**
     * Called when a "good" row is seen (good solely in that it has the
     * correct number of fields).
     *
     * @param fieldvals List of the string values for each field.
     *
     * @return Tuple `(good, keep_going)` where `good` indicates whether
     *   the given row was truly "good" (and hence processed, rather than
     *   skipped), and `keep_going` indicates whether processing of further
     *   rows should continue or stop.  If the return value indicates that
     *   the row isn't actually good, `handle_bad_row` will be called.
     */
    def process_row(fieldvals: Seq[String]): (Boolean, Boolean)

    /* Also,

    def process_lines(lines: Iterator[String],
        filehand: FileHandler, file: String,
        compression: String, realname: String): (Boolean, T)

       A simple implementation simply loops over all the lines and calls
       parse_row() on each one.
    */

    /******************* MAY BE IMPLEMENTED (OVERRIDDEN) *******************/

    /**
     * Called when a bad row is seen.  By default, output a warning.
     *
     * @param line Text of the row.
     * @param fieldvals Field values parsed from the row.
     */
    def handle_bad_row(line: String, fieldvals: Seq[String]) {
      val lineno = num_processed + 1
      if (fieldnames.length != fieldvals.length) {
        warning(
          """Line %s: Bad record, expected %s fields, saw %s fields;
          skipping line=%s""", lineno, fieldnames.length, fieldvals.length,
          line)
      } else {
        warning("""Line %s: Bad record; skipping line=%s""", lineno, line)
      }
      num_bad += 1
    }

    /* Also, the same "may-be-implemented" functions from the superclass
       LineProcessor. */
  }

  /**
   * An object describing a textdb schema, i.e. a description of each of the
   * fields in a textdb, along with "fixed fields" containing the same
   * value for every row.
   *
   * @param fieldnames List of the name of each field
   * @param fixed_values Map specifying additional fields possessing the
   *   same value for every row.  This is optional, but usually at least
   *   the "corpus-name" field should be given, with the name of the corpus
   *   (currently used purely for identification purposes).
   */
  class Schema(
    val fieldnames: Seq[String],
    val fixed_values: Map[String, String] = Map[String, String]()
  ) {

    val field_indices = fieldnames.zipWithIndex.toMap

    def check_values_fit_schema(fieldvals: Seq[String]) {
      if (fieldvals.length != fieldnames.length)
        throw FileFormatException(
          "Wrong-length line, expected %d fields, found %d: %s" format (
            fieldnames.length, fieldvals.length, fieldvals))
    }

    def get_field(fieldvals: Seq[String], key: String,
        error_if_missing: Boolean = true) =
      get_field_or_else(fieldvals, key, error_if_missing = error_if_missing)

    def get_field_or_else(fieldvals: Seq[String], key: String,
        default: String = null, error_if_missing: Boolean = false): String = {
      check_values_fit_schema(fieldvals)
      if (field_indices contains key)
        fieldvals(field_indices(key))
      else
        get_fixed_field(key, default, error_if_missing)
    }

    def get_fixed_field(key: String, default: String = null,
        error_if_missing: Boolean = false) = {
      if (fixed_values contains key)
        fixed_values(key)
      else
        Schema.error_or_default(key, default, error_if_missing)
    }

    /**
     * Output the schema to a file.
     */
    def output_schema_file(filehand: FileHandler, schema_file: String,
        split_text: String = "\t") {
      val schema_outstream = filehand.openw(schema_file)
      schema_outstream.println(fieldnames mkString split_text)
      for ((field, value) <- fixed_values)
        schema_outstream.println(Seq(field, value) mkString split_text)
      schema_outstream.close()
    }

    /**
     * Output the schema to a file.  The file will be named
     * `DIR/PREFIX-SUFFIX-schema.txt`.
     */
    def output_constructed_schema_file(filehand: FileHandler, dir: String,
        prefix: String, suffix: String, split_text: String = "\t") {
      val schema_file = Schema.construct_schema_file(filehand, dir, prefix,
        suffix)
      output_schema_file(filehand, schema_file, split_text)
    }

    /**
     * Output a row describing a document.
     *
     * @param outstream The output stream to write to, as returned by
     *   `open_document_file`.
     * @param fieldvals Iterable describing the field values to be written.
     *   There should be as many items as there are field names in the
     *   `fieldnames` field of the schema.
     */
    def output_row(outstream: PrintStream, fieldvals: Seq[String],
        split_text: String = "\t") {
      assert(fieldvals.length == fieldnames.length,
        "values %s (length %s) not same length as fields %s (length %s)" format
          (fieldvals, fieldvals.length, fieldnames,
            fieldnames.length))
      outstream.println(fieldvals mkString split_text)
    }
  }

  /**
   * A Schema that can be used to select some fields from a larger schema.
   *
   * @param fieldnames Names of fields in this schema; should be a subset of
   *   the field names in `orig_schema`
   * @param fixed_values Fixed values in this schema
   * @param orig_schema Original schema from which fields have been selected.
   */
  class SubSchema(
    fieldnames: Seq[String],
    fixed_values: Map[String, String] = Map[String, String](),
    val orig_schema: Schema
  ) extends Schema(fieldnames, fixed_values) {
    val orig_field_indices =
      orig_schema.field_indices.filterKeys(fieldnames contains _).values.toSet

    /**
     * Given a set of field values corresponding to the original schema
     * (`orig_schema`), produce a list of field values corresponding to this
     * schema.
     */
    def map_original_fieldvals(fieldvals: Seq[String]) =
      fieldvals.zipWithIndex.
        filter { case (x, ind) => orig_field_indices contains ind }.
        map { case (x, ind) => x }
  }

  object Schema {
    /**
     * Construct the name of a schema file, based on the given file handler,
     * directory, prefix and suffix.  The file will end with "-schema.txt".
     */
    def construct_schema_file(filehand: FileHandler, dir: String,
        prefix: String, suffix: String) =
      TextDBProcessor.construct_output_file(filehand, dir, prefix,
        suffix, "-schema.txt")

    /**
     * Read the given schema file.
     *
     * @param filehand File handler of schema file name.
     * @param schema_file Name of the schema file.
     * @param split_re Regular expression used to split the fields of the
     *   schema file, usually TAB. (There's only one row, and each field in
     *   the row gives the name of the corresponding field in the document
     *   file.)
     */
    def read_schema_file(filehand: FileHandler, schema_file: String,
        split_re: String = "\t") = {
      val lines = filehand.openr(schema_file)
      val fieldname_line = lines.next()
      val fieldnames = fieldname_line.split(split_re, -1)
      for (field <- fieldnames if field.length == 0)
        throw new FileFormatException(
          "Blank field name in schema file %s: fields are %s".
          format(schema_file, fieldnames))
      var fixed_fields = Map[String,String]()
      for (line <- lines) {
        val fixed = line.split(split_re, -1)
        if (fixed.length != 2)
          throw new FileFormatException(
            "For fixed fields (i.e. lines other than first) in schema file %s, should have two values (FIELD and VALUE), instead of %s".
            format(schema_file, line))
        val Array(from, to) = fixed
        if (from.length == 0)
          throw new FileFormatException(
            "Blank field name in fxed-value part of schema file %s: line is %s".
              format(schema_file, line))
        fixed_fields += (from -> to)
      }
      new Schema(fieldnames, fixed_fields)
    }

    def get_field(fieldnames: Seq[String], fieldvals: Seq[String], key: String,
        error_if_missing: Boolean = true) =
      get_field_or_else(fieldnames, fieldvals, key,
        error_if_missing = error_if_missing)

    def get_field_or_else(fieldnames: Seq[String], fieldvals: Seq[String],
        key: String, default: String = null,
        error_if_missing: Boolean = false): String = {
      assert(fieldvals.length == fieldnames.length)
      var i = 0
      while (i < fieldnames.length) {
        if (fieldnames(i) == key) return fieldvals(i)
        i += 1
      }
      return error_or_default(key, default, error_if_missing)
    }

    protected def error_or_default(key: String, default: String,
        error_if_missing: Boolean) = {
      if (error_if_missing) {
        throw new NoSuchElementException("key not found: %s" format key)
      } else default
    }

    /**
     * Convert a set of field names and values to a map, to make it easier
     * to work with them.  The result is a mutable order-preserving map,
     * which is important so that when converted back to separate lists of
     * names and values, the values are still written out correctly.
     * (The immutable order-preserving ListMap isn't sufficient since changing
     * a field value results in the field getting moved to the end.)
     *
     */
    def to_map(fieldnames: Seq[String], fieldvals: Seq[String]) =
      mutable.LinkedHashMap[String, String]() ++ (fieldnames zip fieldvals)

    /**
     * Convert from a map back to a tuple of lists of field names and values.
     */
    def from_map(map: mutable.Map[String, String]) =
      map.toSeq.unzip

  }

  /**
   * File processor for reading in a set of records in "textdb" format.
   * The database has the following format:
   *
   * (1) The documents are stored as field-text files, separated by a TAB
   *     character.
   * (2) There is a corresponding schema file, which lists the names of
   *     each field, separated by a TAB character, as well as any
   *     "fixed" fields that have the same value for all rows (one per
   *     line, with the name, a TAB, and the value).
   * (3) The document and schema files are identified by a suffix.
   *     The document files are named `DIR/PREFIX-SUFFIX.txt`
   *     (or `DIR/PREFIX-SUFFIX.txt.bz2` or similar, for compressed files),
   *     while the schema file is named `DIR/PREFIX-SUFFIX-schema.txt`.
   *     Note that the SUFFIX is set when the `TextDBLineProcessor` is
   *     created, and typically specifies the category of corpus being
   *     read (e.g. "text" for corpora containing text or "unigram-counts"
   *     for a corpus containing unigram counts).  The directory is specified
   *     in a particular call to `process_files` or `read_schema_from_textdb`.
   *     The prefix is arbitrary and descriptive -- i.e. any files in the
   *     appropriate directory and with the appropriate suffix, regardless
   *     of prefix, will be loaded.  The prefix of the currently-loading
   *     document file is available though the field `current_document_prefix`.
   *
   * The most common setup is to have the schema file and any document files
   * placed in the same directory, although it's possible to have them in
   * different directories or to have document files scattered across multiple
   * directories.  Note that the naming of the files allows for multiple
   * document files in a single directory, as well as multiple corpora to
   * coexist in the same directory, as long as they have different suffixes.
   * This is often used to present different "views" onto the same corpus
   * (e.g. one containing raw text, one containing unigram counts, etc.), or
   * different splits (e.g. training vs. dev vs. test). (In fact, it is
   * common to divide a corpus into sub-corpora according to the split.
   * In such a case, document files will be named `DIR/PREFIX-SPLIT-SUFFIX.txt`
   * or similar.  This allows all files for all splits to be located using a
   * suffix consisting only of the final "SUFFIX" part, while a particular
   * split can be located using a larger prefix of the form "SPLIT-SUFFIX".)
   *
   * Generally, after creating a file processor of this sort, the schema
   * file needs to be read using `read_schema_from_textdb`; then the document
   * files can be processed using `process_files`.  Most commonly, the same
   * directory is passed to both functions.  In more complicated setups,
   * however, different directory names can be used; multiple calls to
   * `process_files` can be made to process multiple directories; or
   * individual file names can be given to `process_files` for maximum
   * control.
   *
   * Various fields store things like the current directory and file prefix
   * (the part before the suffix).
   *
   * @param suffix the suffix of the corpus files, as described above
   *     
   */
  abstract class TextDBLineProcessor[T](
    suffix: String
  ) extends LineProcessor[T] {
    import TextDBProcessor._

    /**
     * Name of the schema file.
     */
    var schema_file: String = _
    /**
     * File handler of the schema file.
     */
    var schema_filehand: FileHandler = _
    /**
     * Directory of the schema file.
     */
    var schema_dir: String = _
    /**
     * Prefix of the schema file (see above).
     */
    var schema_prefix: String = _
    /**
     * Schema read from the schema file.
     */
    var schema: Schema = _

    /**
     * Current document file being read.
     */
    var current_document_file: String = _
    /**
     * File handler of the current document file.
     */
    var current_document_filehand: FileHandler = _
    /**
     * "Real name" of the current document file, after any compression suffix
     * has been removed.
     */
    var current_document_realname: String = _
    /**
     * Type of compression of the current document file.
     */
    var current_document_compression: String = _
    /**
     * Directory of the current document file.
     */
    var current_document_dir: String = _
    /**
     * Prefix of the current document file (see above).
     */
    var current_document_prefix: String = _

    def set_schema(schema: Schema) {
      this.schema = schema
    }

    override def begin_process_lines(lines: Iterator[String],
        filehand: FileHandler, file: String,
        compression: String, realname: String) {
      current_document_compression = compression
      current_document_filehand = filehand
      current_document_file = file
      current_document_realname = realname
      val (dir, base) = filehand.split_filename(realname)
      current_document_dir = dir
      current_document_prefix = base.stripSuffix("-" + suffix + ".txt")
      super.begin_process_lines(lines, filehand, file, compression, realname)
    }

    /**
     * Locate and read the schema file of the appropriate suffix in the
     * given directory.  Set internal variables containing the schema file
     * and schema.
     */
    def read_schema_from_textdb(filehand: FileHandler, dir: String) {
      schema_file = find_schema_file(filehand, dir, suffix)
      schema_filehand = filehand
      val (_, base) = filehand.split_filename(schema_file)
      schema_dir = dir
      schema_prefix = base.stripSuffix("-" + suffix + "-schema.txt")
      val schema = Schema.read_schema_file(filehand, schema_file)
      set_schema(schema)
    }

    /**
     * List only the document files of the appropriate suffix.
     */
    override def list_files(filehand: FileHandler, dir: String) = {
      val filter = make_document_file_suffix_regex(suffix)
      val files = filehand.list_files(dir)
      for (file <- files if filter.findFirstMatchIn(file) != None) yield file
    }
  }

  object TextDBProcessor {
    val possible_compression_re = """(\.bz2|\.bzip2|\.gz|\.gzip)?$"""
    /**
     * For a given suffix, create a regular expression
     * ([[scala.util.matching.Regex]]) that matches document files of the
     * suffix.
     */
    def make_document_file_suffix_regex(suffix: String) = {
      val re_quoted_suffix = """-%s\.txt""" format suffix
      (re_quoted_suffix + possible_compression_re).r
    }
    /**
     * For a given suffix, create a regular expression
     * ([[scala.util.matching.Regex]]) that matches schema files of the
     * suffix.
     */
    def make_schema_file_suffix_regex(suffix: String) = {
      val re_quoted_suffix = """-%s-schema\.txt""" format suffix
      (re_quoted_suffix + possible_compression_re).r
    }

    /**
     * Construct the name of a file (either schema or document file), based
     * on the given file handler, directory, prefix, suffix and file ending.
     * For example, if the file ending is "-schema.txt", the file will be
     * named `DIR/PREFIX-SUFFIX-schema.txt`.
     */
    def construct_output_file(filehand: FileHandler, dir: String,
        prefix: String, suffix: String, file_ending: String) = {
      val new_base = prefix + "-" + suffix + file_ending
      filehand.join_filename(dir, new_base)
    }

    /**
     * Locate the schema file of the appropriate suffix in the given directory.
     */
    def find_schema_file(filehand: FileHandler, dir: String, suffix: String) = {
      val schema_regex = make_schema_file_suffix_regex(suffix)
      val all_files = filehand.list_files(dir)
      val files =
        (for (file <- all_files
          if schema_regex.findFirstMatchIn(file) != None) yield file).toSeq
      if (files.length == 0)
        throw new FileFormatException(
          "Found no schema files (matching %s) in directory %s"
          format (schema_regex, dir))
      if (files.length > 1)
        throw new FileFormatException(
          "Found multiple schema files (matching %s) in directory %s: %s"
          format (schema_regex, dir, files))
      files(0)
    }

    /**
     * Locate and read the schema file of the appropriate suffix in the
     * given directory.
     */
    def read_schema_from_textdb(filehand: FileHandler, dir: String,
          suffix: String) = {
      val schema_file = find_schema_file(filehand, dir, suffix)
      Schema.read_schema_file(filehand, schema_file)
    }

    /**
     * Return a list of shell-style wildcard patterns matching all the document
     * files in the given directory with the given suffix (including compressed
     * files).
     */
    def get_matching_patterns(filehand: FileHandler, dir: String,
        suffix: String) = {
      val possible_endings = Seq("", ".bz2", ".bzip2", ".gz", ".gzip")
      for {ending <- possible_endings
           full_ending = "-%s.txt%s" format (suffix, ending)
           pattern = filehand.join_filename(dir, "*%s" format full_ending)
           all_files = filehand.list_files(dir)
           files = all_files.filter(_ endsWith full_ending)
           if files.toSeq.length > 0}
        yield pattern
    }
  }

  /**
   * A basic file processor for reading a "corpus" of records in
   * textdb format. You might want to use the higher-level
   * `TextDBProcessor`.
   *
   * FIXME: Should probably eliminate this.
   *
   * @param suffix the suffix of the corpus files, as described above
   */
  abstract class BasicTextDBProcessor[T](
    suffix: String
  ) extends TextDBLineProcessor[T](suffix) with FieldLineProcessor[T] {
    override def set_schema(schema: Schema) {
      super.set_schema(schema)
      set_fieldnames(schema.fieldnames)
    }
  }

  class ExitTextDBProcessor[T](val value: Option[T]) extends Throwable { }

  /**
   * A file processor for reading in a "corpus" of records in textdb
   * format (where each record or "row" is a single line, with fields
   * separated by TAB) and processing each one.  Each row is assumed to
   * generate an object of type T.  The result of calling `process_file`
   * will be a Seq[T] of all objects, and the result of calling
   * `process_files` to process all files will be a Seq[Seq[T]], one per
   * file.
   *
   * You should implement `handle_row`, which is passed in the field
   * values, and should return either `Some(x)` for x of type T, if
   * you were able to process the row, or `None` otherwise.  If you
   * want to exit further processing, throw a `ExitTextDBProcessor(value)`,
   * where `value` is either `Some(x)` or `None`, as for a normal return
   * value.
   *
   * @param suffix the suffix of the corpus files, as described above
   */
  abstract class TextDBProcessor[T](suffix: String) extends
      BasicTextDBProcessor[Seq[T]](suffix) {

    def handle_row(fieldvals: Seq[String]): Option[T]

    val values_so_far = mutable.ListBuffer[T]()

    def process_row(fieldvals: Seq[String]): (Boolean, Boolean) = {
      try {
        handle_row(fieldvals) match {
          case Some(x) => { values_so_far += x; return (true, true) }
          case None => { return (false, true) }
        }
      } catch {
        case exit: ExitTextDBProcessor[T] => {
          exit.value match {
            case Some(x) => { values_so_far += x; return (true, false) }
            case None => { return (false, false) }
          }
        }
      }
    }

    val task = new MeteredTask("document", "reading")
    def process_lines(lines: Iterator[String],
        filehand: FileHandler, file: String,
        compression: String, realname: String) = {
      var should_stop = false
      values_so_far.clear()
      breakable {
        for (line <- lines) {
          if (!parse_row(line)) {
            should_stop = true
            break
          }
        }
      }
      (!should_stop, values_so_far.toSeq)
    }

    override def end_processing(filehand: FileHandler, files: Iterable[String]) {
      task.finish()
      super.end_processing(filehand, files)
    }

    /**
     * Read a corpus from a directory and return the result of processing the
     * rows in the corpus. (If you want more control over the processing,
     * call `read_schema_from_textdb` and then `process_files`.  This allows,
     * for example, reading files from multiple directories with a single
     * schema, or determining whether processing was aborted early or allowed
     * to run to completion.)
     *
     * @param filehand File handler object of the directory
     * @param dir Directory to read
     *
     * @return A sequence of sequences of values.  There is one inner sequence
     *   per file read in, and each such sequence contains all the values
     *   read from the file. (There may be fewer values than rows in a file
     *   if some rows were rejected by `handle_row`, and there may be fewer
     *   files read than exist in the directory if `handle_row` signalled that
     *   processing should stop.)
     */
    def read_textdb(filehand: FileHandler, dir: String) = {
      read_schema_from_textdb(filehand, dir)
      val (finished, value) = process_files(filehand, Seq(dir))
      value
    }

    /*
    FIXME: Should be implemented.  Requires that process_files() returns
    the filename along with the value. (Should not be a problem for any
    existing users of BasicTextDBProcessor, because AFAIK they
    ignore the value.)

    def read_textdb_with_filenames(filehand: FileHandler, dir: String) = ...
    */
  }

  /**
   * Class for writing a "corpus" of documents.  The corpus has the
   * format described in `TextDBLineProcessor`.
   *
   * @param schema the schema describing the fields in the document file
   * @param suffix the suffix of the corpus files, as described in
   *   `TextDBLineProcessor`
   *     
   */
  class TextDBWriter(
    val schema: Schema,
    val suffix: String
  ) {
    /**
     * Text used to separate fields.  Currently this is always a tab
     * character, and no provision is made for changing this.
     */
    val split_text = "\t"

    /**
     * Open a document file and return an output stream.  The file will be
     * named `DIR/PREFIX-SUFFIX.txt`, possibly with an additional suffix
     * (e.g. `.bz2`), depending on the specified compression (which defaults
     * to no compression).  Call `output_row` to output a row describing
     * a document.
     */
    def open_document_file(filehand: FileHandler, dir: String,
        prefix: String, compression: String = "none") = {
      val file = TextDBProcessor.construct_output_file(filehand, dir,
        prefix, suffix, ".txt")
      filehand.openw(file, compression = compression)
    }

    /**
     * Output the schema to a file.  The file will be named
     * `DIR/PREFIX-SUFFIX-schema.txt`.
     */
    def output_schema_file(filehand: FileHandler, dir: String,
        prefix: String) {
      schema.output_constructed_schema_file(filehand, dir, prefix, suffix,
        split_text)
    }
  }

  val document_metadata_suffix = "document-metadata"
  val unigram_counts_suffix = "unigram-counts"
  val ngram_counts_suffix = "ngram-counts"
  val text_suffix = "text"

  class EncodeDecode(val chars_to_encode: Seq[Char]) {
    private val encode_chars_regex = "[%s]".format(chars_to_encode mkString "").r
    private val encode_chars_map =
      chars_to_encode.map(c => (c.toString, "%%%02X".format(c.toInt))).toMap
    private val decode_chars_map =
      encode_chars_map.toSeq.flatMap {
        case (dec, enc) => Seq((enc, dec), (enc.toLowerCase, dec)) }.toMap
    private val decode_chars_regex =
      "(%s)".format(decode_chars_map.keys mkString "|").r

    def encode(str: String) =
      encode_chars_regex.replaceAllIn(str, m => encode_chars_map(m.matched))
    def decode(str: String) =
      decode_chars_regex.replaceAllIn(str, m => decode_chars_map(m.matched))
  }

  private val endec_string_for_count_map_field =
    new EncodeDecode(Seq('%', ':', ' ', '\t', '\n', '\r', '\f'))
  private val endec_string_for_sequence_field =
    new EncodeDecode(Seq('%', '>', '\t', '\n', '\r', '\f'))
  private val endec_string_for_whole_field =
    new EncodeDecode(Seq('%', '\t', '\n', '\r', '\f'))

  /**
   * Encode a word for placement inside a "counts" field.  Colons and spaces
   * are used for separation inside of a field, and tabs and newlines are used
   * for separating fields and records.  We need to escape all of these
   * characters (normally whitespace should be filtered out during
   * tokenization, but for some applications it won't necessarily).  We do this
   * using URL-style-encoding, e.g. replacing : by %3A; hence we also have to
   * escape % signs. (We could equally well use HTML-style encoding; then we'd
   * have to escape &amp; instead of :.) Note that regardless of whether we use
   * URL-style or HTML-style encoding, we probably want to do the encoding
   * ourselves rather than use a predefined encoder.  We could in fact use the
   * presupplied URL encoder, but it would encode all sorts of stuff, which is
   * unnecessary and would make the raw files harder to read.  In the case of
   * HTML-style encoding, : isn't even escaped, so that wouldn't work at all.
   */
  def encode_string_for_count_map_field(word: String) =
    endec_string_for_count_map_field.encode(word)

  /**
   * Encode an n-gram into text suitable for the "counts" field.
   The
   * individual words are separated by colons, and each word is encoded
   * using `encode_string_for_count_map_field`.  We need to encode '\n'
   * (record separator), '\t' (field separator), ' ' (separator between
   * word/count pairs), ':' (separator between word and count),
   * '%' (encoding indicator).
   */
  def encode_ngram_for_count_map_field(ngram: Iterable[String]) = {
    ngram.map(encode_string_for_count_map_field) mkString ":"
  }

  /**
   * Decode a word encoded using `encode_string_for_count_map_field`.
   */
  def decode_string_for_count_map_field(word: String) =
    endec_string_for_count_map_field.decode(word)

  /**
   * Encode a string for placement in a field consisting of a sequence
   * of strings.  This is similar to `encode_string_for_count_map_field` except
   * that we don't encode spaces.  We encode '&gt;' for use as a separator
   * inside of a field (since it's almost certain not to occur, because
   * we generally get HTML-encoded text; and even if not, it's fairly
   * rare).
   */
  def encode_string_for_sequence_field(word: String) =
    endec_string_for_sequence_field.encode(word)

  /**
   * Decode a string encoded using `encode_string_for_sequence_field`.
   */
  def decode_string_for_sequence_field(word: String) =
    endec_string_for_sequence_field.decode(word)

  /**
   * Encode a string for placement in a field by itself.  This is similar
   * to `encode_word_for_sequence_field` except that we don't encode the &gt;
   * sign.
   */
  def encode_string_for_whole_field(word: String) =
    endec_string_for_whole_field.encode(word)

  /**
   * Decode a string encoded using `encode_string_for_whole_field`.
   */
  def decode_string_for_whole_field(word: String) =
    endec_string_for_whole_field.decode(word)

  /**
   * Decode an n-gram encoded using `encode_ngram_for_count_map_field`.
   */
  def decode_ngram_for_count_map_field(ngram: String) = {
    ngram.split(":", -1).map(decode_string_for_count_map_field)
  }

  /**
   * Split counts field into the encoded n-gram section and the word count.
   */
  def shallow_split_count_map_field(field: String) = {
    val last_colon = field.lastIndexOf(':')
    if (last_colon < 0)
      throw FileFormatException(
        "Counts field must be of the form WORD:WORD:...:COUNT, but %s seen"
          format field)
    val count = field.slice(last_colon + 1, field.length).toInt
    (field.slice(0, last_colon), count)
  }

  /**
   * Split counts field into n-gram and word count.
   */
  def deep_split_count_map_field(field: String) = {
    val (encoded_ngram, count) = shallow_split_count_map_field(field)
    (decode_ngram_for_count_map_field(encoded_ngram), count)
  }

  /**
   * Serialize a sequence of (encoded-word, count) pairs into the format used
   * in a corpus.  The word or ngram must already have been encoded using
   * `encode_string_for_count_map_field` or `encode_ngram_for_count_map_field`.
   */
  def shallow_encode_count_map(seq: collection.Seq[(String, Int)]) = {
    // Sorting isn't strictly necessary but ensures consistent output as well
    // as putting the most significant items first, for visual confirmation.
    (for ((word, count) <- seq sortWith (_._2 > _._2)) yield
      ("%s:%s" format (word, count))) mkString " "
  }

  /**
   * Serialize a sequence of (word, count) pairs into the format used
   * in a corpus.
   */
  def encode_count_map(seq: collection.Seq[(String, Int)]) = {
    shallow_encode_count_map(seq map {
      case (word, count) => (encode_string_for_count_map_field(word), count)
    })
  }

  /**
   * Deserialize an encoded word-count map into a sequence of
   * (word, count) pairs.
   */
  def decode_count_map(encoded: String) = {
    if (encoded.length == 0)
      Array[(String, Int)]()
    else
      {
      val wordcounts = encoded.split(" ")
      for (wordcount <- wordcounts) yield {
        val split_wordcount = wordcount.split(":", -1)
        if (split_wordcount.length != 2)
          throw FileFormatException(
            "For unigram counts, items must be of the form WORD:COUNT, but %s seen"
            format wordcount)
        val Array(word, strcount) = split_wordcount
        if (word.length == 0)
          throw FileFormatException(
            "For unigram counts, WORD in WORD:COUNT must not be empty, but %s seen"
            format wordcount)
        val count = strcount.toInt
        val decoded_word = decode_string_for_count_map_field(word)
        (decoded_word, count)
      }
    }
  }

  object Encoder {
    def count_map(x: collection.Map[String, Int]) = encode_count_map(x.toSeq)
    def count_map_seq(x: collection.Seq[(String, Int)]) = encode_count_map(x)
    def string(x: String) = encode_string_for_whole_field(x)
    def string_in_seq(x: String) = encode_string_for_sequence_field(x)
    def seq_string(x: collection.Seq[String]) =
      x.map(encode_string_for_sequence_field) mkString ">>"
    def timestamp(x: Long) = x.toString
    def long(x: Long) = x.toString
    def int(x: Int) = x.toString
    def double(x: Double) = x.toString
  }

  object Decoder {
    def count_map(x: String) = decode_count_map(x).toMap
    def count_map_seq(x: String) = decode_count_map(x)
    def string(x: String) = decode_string_for_whole_field(x)
    def seq_string(x: String) =
      x.split(">>", -1).map(decode_string_for_sequence_field)
    def timestamp(x: String) = x.toLong
    def long(x: String) = x.toLong
    def int(x: String) = x.toInt
    def double(x: String) = x.toDouble
  }

}
