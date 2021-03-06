package fr.animenfance.partenaire.service;

import static fr.animenfance.utils.IndexUtils.createDocumentFromPartenaire;

import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.stream.Collectors;

import javax.annotation.PostConstruct;

import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.standard.StandardAnalyzer;
import org.apache.lucene.document.Document;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.queryparser.classic.ParseException;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.ScoreDoc;
import org.apache.lucene.store.RAMDirectory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import com.google.common.base.Stopwatch;
import fr.animenfance.bean.Partenaire;
import fr.animenfance.dao.PartenaireDao;
import fr.animenfance.utils.IndexUtils;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
public class IndexerService {

  private final static Queue<String> REBUILD_ORDERS = new ConcurrentLinkedQueue<>();

  private final Analyzer analyzer;
  private final PartenaireDao dao;

  private RAMDirectory index = new RAMDirectory();

  @Autowired
  public IndexerService(PartenaireDao dao) {
    analyzer = new StandardAnalyzer();
    this.dao = dao;
  }

  @PostConstruct
  public void postConstruct() {
    scheduleIndexRebuild();
  }

  public void scheduleIndexRebuild() {
    log.debug("Index rebuild scheduled.");
    REBUILD_ORDERS.add("rebuild");
  }

  @Scheduled(initialDelay = 1000, fixedDelay = 2000)
  public void rebuildIndex() throws IOException {

    String command = REBUILD_ORDERS.poll();

    if(command != null) {
      Stopwatch watch = Stopwatch.createStarted();
      log.debug("Starting rebuild index...");

      clearIndex();

      List<Partenaire> partenaires = dao.list();

      try(IndexWriter indexWriter = new IndexWriter(index, new IndexWriterConfig(analyzer))) {
        for (Partenaire partenaire : partenaires) {
          Document doc = createDocumentFromPartenaire(partenaire);
          indexWriter.addDocument(doc);
        }

        indexWriter.flush();
      }

      log.info("Index rebuild, " + partenaires.size() + " items in " +
        watch.toString());
    }
  }

  private void clearIndex() {
    index.close();
    index = new RAMDirectory();
  }

  public long getIndexSize() {
    return index.ramBytesUsed();
  }

  public List<Partenaire> searchPartenaire(String search, int maxResultNumber) throws IOException, ParseException {
    try (DirectoryReader iReader = DirectoryReader.open(index)) {
      Query query = IndexUtils.getQueryForPartenaire(search, analyzer);

      IndexSearcher iSearcher = new IndexSearcher(iReader);
      ScoreDoc[] hits = iSearcher.search(query, maxResultNumber).scoreDocs;

      return Arrays.stream(hits).map(scoreDoc -> getPartenaireFromSearcher(iSearcher, scoreDoc))
        .collect(Collectors.toList());
    }
  }

  private static Partenaire getPartenaireFromSearcher(IndexSearcher iSearcher, ScoreDoc scoreDoc) {
    try {
      return IndexUtils.createPartenaireFromDocument(iSearcher.doc(scoreDoc.doc));
    } catch (IOException e) {
      log.error(e.getLocalizedMessage(), e);
      return null;
    }
  }
}
