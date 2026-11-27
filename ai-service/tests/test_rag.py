import math
import os
from contextlib import closing
from unittest import TestCase, skipUnless
from unittest.mock import patch
from uuid import uuid4

from fastapi.testclient import TestClient

from rag import main


class EmbeddingTest(TestCase):
    def test_padding_preserves_cosine_similarity(self):
        query = [0.8, 0.6] + [0.0] * 1022
        candidate = [0.6, 0.8] + [0.0] * 1022
        padded_query = main.padded_embedding(query)
        padded_candidate = main.padded_embedding(candidate)
        self.assertEqual(len(padded_query), 1536)
        self.assertTrue(math.isclose(math.dist(query, candidate), math.dist(padded_query, padded_candidate)))
        self.assertEqual(sum(a * b for a, b in zip(query, candidate)),
                         sum(a * b for a, b in zip(padded_query, padded_candidate)))

    def test_reject_invalid_vectors(self):
        for values in ([1.0], [0.0] * 1024, [math.nan] * 1024):
            with self.assertRaises(ValueError):
                main.padded_embedding(values)


@skipUnless(os.getenv("PEANUT_TEST_RAG") == "true", "Requires a disposable PostGIS/pgvector database")
class PgvectorTest(TestCase):
    def test_embed_and_rank_with_real_pgvector(self):
        client = TestClient(main.app)
        title_prefix = "vector-test-" + uuid4().hex
        field_good = [1.0, 0.0] + [0.0] * 1022
        field_other = [0.0, 1.0] + [0.0] * 1022
        with patch.object(main, "embedding_model") as model:
            model.return_value.embed_query.side_effect = (
                lambda text: field_good if "花针" in text else field_other
            )
            try:
                first = client.post("/embed", json={"source_title": title_prefix + "-1", "content": "花针期管理"}).json()
                second = client.post("/embed", json={"source_title": title_prefix + "-2", "content": "成熟期管理"}).json()
                matches = main.retrieve("花针期需要注意什么")
                self.assertEqual(matches[0][0], first["chunk_id"])
                self.assertNotIn(second["chunk_id"], [row[0] for row in matches])
                with closing(main.database()) as connection, connection, connection.cursor() as cursor:
                    cursor.execute("SELECT vector_dims(embedding) FROM knowledge_chunk WHERE id = %s",
                                   (first["chunk_id"],))
                    self.assertEqual(cursor.fetchone()[0], 1536)
            finally:
                with closing(main.database()) as connection, connection, connection.cursor() as cursor:
                    cursor.execute("DELETE FROM knowledge_chunk WHERE source_title LIKE %s", (title_prefix + "%",))
