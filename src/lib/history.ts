const DB_NAME = "chengshu";
const STORE = "books";
const MAX_BOOKS = 12;

export type StoredBook = {
  id: string;
  title: string;
  sourceUrl: string;
  filename: string;
  createdAt: number;
  size: number;
  byline: string;
  siteName: string;
  excerpt: string;
  blob: Blob;
  html?: string;
};

type BookRecord = Omit<StoredBook, "blob"> & { bytes: ArrayBuffer };

function openDb(): Promise<IDBDatabase> {
  return new Promise((resolve, reject) => {
    const req = indexedDB.open(DB_NAME, 1);
    req.onupgradeneeded = () => {
      const db = req.result;
      if (!db.objectStoreNames.contains(STORE)) {
        db.createObjectStore(STORE, { keyPath: "id" });
      }
    };
    req.onsuccess = () => resolve(req.result);
    req.onerror = () => reject(req.error);
  });
}

function recordToBook(row: BookRecord): StoredBook {
  return {
    id: row.id,
    title: row.title,
    sourceUrl: row.sourceUrl,
    filename: row.filename,
    createdAt: row.createdAt,
    size: row.size,
    byline: row.byline,
    siteName: row.siteName,
    excerpt: row.excerpt,
    html: row.html,
    blob: new Blob([row.bytes], { type: "application/epub+zip" }),
  };
}

export async function saveBook(book: StoredBook): Promise<void> {
  const db = await openDb();
  const bytes = await book.blob.arrayBuffer();
  await new Promise<void>((resolve, reject) => {
    const tx = db.transaction(STORE, "readwrite");
    tx.oncomplete = () => resolve();
    tx.onerror = () => reject(tx.error);
    tx.objectStore(STORE).put({
      id: book.id,
      title: book.title,
      sourceUrl: book.sourceUrl,
      filename: book.filename,
      createdAt: book.createdAt,
      size: book.size,
      byline: book.byline,
      siteName: book.siteName,
      excerpt: book.excerpt,
      html: book.html,
      bytes,
    } satisfies BookRecord);
  });
  const all = await listBooks();
  if (all.length > MAX_BOOKS) {
    const extra = all.slice(MAX_BOOKS);
    await Promise.all(extra.map((item) => deleteBook(item.id)));
  }
}

export async function listBooks(): Promise<StoredBook[]> {
  const db = await openDb();
  const rows = await new Promise<BookRecord[]>((resolve, reject) => {
    const tx = db.transaction(STORE, "readonly");
    const req = tx.objectStore(STORE).getAll();
    req.onsuccess = () => resolve((req.result as BookRecord[]) ?? []);
    req.onerror = () => reject(req.error);
  });
  return rows.map(recordToBook).sort((a, b) => b.createdAt - a.createdAt);
}

export async function deleteBook(id: string): Promise<void> {
  const db = await openDb();
  await new Promise<void>((resolve, reject) => {
    const tx = db.transaction(STORE, "readwrite");
    tx.oncomplete = () => resolve();
    tx.onerror = () => reject(tx.error);
    tx.objectStore(STORE).delete(id);
  });
}
