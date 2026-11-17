package db.migration;

import com.bandyakama.conversation.ConversationTitles;
import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Les conversations restées « Nouvelle conversation » prennent le titre de leur
 * première question, avec la même règle que les nouvelles (ConversationTitles).
 * Un nom choisi par l'utilisateur n'est pas touché.
 */
public class V11__titres_des_conversations extends BaseJavaMigration {

    @Override
    public void migrate(Context context) throws Exception {
        if (!tableExiste(context, "bandy_conversation_message")) {
            return; // une base plus ancienne, sans messages : rien à renommer
        }
        Map<Object, String> titres = new LinkedHashMap<>();
        try (PreparedStatement lecture = context.getConnection().prepareStatement("""
                SELECT m.conversation_id, m.content
                FROM bandy_conversation_message m
                JOIN bandy_conversation c ON c.id = m.conversation_id
                WHERE c.label = ? AND m.role = 'USER'
                ORDER BY m.conversation_id, m.sequence_number
                """)) {
            lecture.setString(1, ConversationTitles.PAR_DEFAUT);
            try (ResultSet ligne = lecture.executeQuery()) {
                while (ligne.next()) {
                    titres.putIfAbsent(ligne.getObject(1),
                            ConversationTitles.depuisPremierMessage(ligne.getString(2)));
                }
            }
        }
        try (PreparedStatement maj = context.getConnection().prepareStatement(
                "UPDATE bandy_conversation SET label = ? WHERE id = ? AND label = ?")) {
            for (Map.Entry<Object, String> titre : titres.entrySet()) {
                maj.setString(1, titre.getValue());
                maj.setObject(2, titre.getKey());
                maj.setString(3, ConversationTitles.PAR_DEFAUT);
                maj.addBatch();
            }
            maj.executeBatch();
        }
    }

    private static boolean tableExiste(Context context, String nom) throws java.sql.SQLException {
        java.sql.DatabaseMetaData meta = context.getConnection().getMetaData();
        for (String variante : new String[] {nom, nom.toUpperCase(java.util.Locale.ROOT)}) {
            try (ResultSet tables = meta.getTables(null, null, variante, new String[] {"TABLE"})) {
                if (tables.next()) {
                    return true;
                }
            }
        }
        return false;
    }
}
