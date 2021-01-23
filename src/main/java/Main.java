import com.google.gson.JsonObject;
import com.vk.api.sdk.client.TransportClient;
import com.vk.api.sdk.client.VkApiClient;
import com.vk.api.sdk.client.actors.GroupActor;
import com.vk.api.sdk.exceptions.ApiException;
import com.vk.api.sdk.exceptions.ClientException;
import com.vk.api.sdk.exceptions.LongPollServerKeyExpiredException;
import com.vk.api.sdk.httpclient.HttpTransportClient;
import com.vk.api.sdk.objects.callback.longpoll.responses.GetLongPollEventsResponse;
import com.vk.api.sdk.objects.groups.responses.GetLongPollServerResponse;
import com.vk.api.sdk.objects.photos.responses.MessageUploadResponse;
import org.json.JSONArray;
import org.json.JSONObject;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.*;
import java.net.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.ThreadLocalRandom;

public class Main {

    static int groupId;
    static String access_token;
    static VkApiClient vk;
    static GroupActor groupActor;

    public static void main(String[] args) throws ClientException, ApiException, IOException {
        Properties properties = new Properties();
        properties.load(new FileReader("maindata.properties"));
        access_token = properties.getProperty("access_token");
        groupId = Integer.parseInt(properties.getProperty("groupId"));
        TransportClient transportClient = HttpTransportClient.getInstance();
        vk = new VkApiClient(transportClient);
        groupActor = new GroupActor(groupId, access_token);

        GetLongPollServerResponse longPollServer = vk.groups().getLongPollServer(groupActor, groupId).execute();
        String ts = longPollServer.getTs();
        while (true) {
            try {
                GetLongPollEventsResponse query = vk.longPoll().getEvents(longPollServer.getServer(), longPollServer.getKey(), ts)
                        .waitTime(10)
                        .execute();
                ts = query.getTs();

                List<JsonObject> updates = query.getUpdates();
                for (JsonObject object : updates) {
                    String string = object.toString();
                    JSONObject jsonObject = new JSONObject(string);
                    if (jsonObject.getString("type").equals("message_new")) {
                        JSONObject message = jsonObject.getJSONObject("object").getJSONObject("message");
                        if (message.has("attachments")) {
                            JSONArray attachments = message.getJSONArray("attachments");
                            for (Object l : attachments) {
                                JSONObject k = (JSONObject) l;
                                if (k.get("type").equals("photo")) {
                                    int maxWidth = 0;
                                    System.out.println(query);
                                    String resultUrl = "";
                                    for (Object obj : k.getJSONObject("photo").getJSONArray("sizes")) {
                                        JSONObject json = (JSONObject) obj;
                                        int jWidth = json.getInt("width");
                                        if (jWidth > maxWidth) {
                                            maxWidth = jWidth;
                                            resultUrl = json.getString("url");
                                        }
                                    }
                                    new Poster(resultUrl, message.getInt("from_id")).run();
                                }
                            }
                        } else {
                            sendMessage("Упс, не нашёл картинку для переотправки...", message.getInt("from_id"), ts);
                        }
                    }
                }
            } catch (LongPollServerKeyExpiredException e) {
                //key is expired
                longPollServer = vk.groups().getLongPollServer(groupActor, groupId).execute();
                ts = longPollServer.getTs();
            }
        }
    }

    public static void sendMessage(String message, int userId, String ts) throws ClientException, ApiException {
        vk.messages().send(groupActor)
                .randomId(ThreadLocalRandom.current().nextInt())
                .userId(userId)
                .peerId(groupActor.getId())
                .message(message)
                .execute();
    }

    public static class Poster implements Runnable {
        private String url;
        private int userId;

        public Poster(String url, int userId) {
            this.url = url;
            this.userId = userId;
        }

        @Override
        public void run() {
            BufferedImage img = null;
            File file = new File("test" + ".png");
            try {
                vk.messages().send(groupActor)
                        .randomId(ThreadLocalRandom.current().nextInt())
                        .userId(userId)
                        .peerId(groupActor.getId())
                        .message("Распознана картинка - начинаю работу")
                        .execute();
                img = ImageIO.read(new URL(url));
                int name = ThreadLocalRandom.current().nextInt();
                while (file.exists()) {
                    name = ThreadLocalRandom.current().nextInt();
                    file = new File(name + ".png");
                }
                file.createNewFile();
                ImageIO.write(img, "png", file);

            URI photoUploadUrl = null;
            try {
                photoUploadUrl = vk.photos().getMessagesUploadServer(groupActor)
                        .execute()
                        .getUploadUrl();
            } catch (final ApiException | ClientException e) {
                //blyat
            }
            List<MessageUploadResponse> uploads = new ArrayList<>();
            uploads.add(vk.upload().photoMessage(photoUploadUrl.toString(), file).execute());
            file.delete();
            StringBuilder script = new StringBuilder("var a;");
            for (int i = 0; i < uploads.size(); i++) {
                MessageUploadResponse upload = uploads.get(i);
                Integer serv = upload.getServer();
                String photos = upload.getPhoto();
                String hash = upload.getHash();
                URL request = new URL("https://api.vk.com/method/photos.saveMessagesPhoto?photo=" + photos + "&server=" + serv + "&hash=" + hash + "&access_token=" + access_token + "&v=5.126");
                HttpURLConnection con = (HttpURLConnection) request.openConnection();
                con.setRequestMethod("GET");
                int respCode = con.getResponseCode();
                BufferedReader in = new BufferedReader(new InputStreamReader(
                        con.getInputStream()));
                String inputLine;
                StringBuffer response = new StringBuffer();

                while ((inputLine = in.readLine()) != null) {
                    response.append(inputLine);
                }
                in.close();
                JSONObject res = new JSONObject(response.toString());
                //atts.add("photo"+k.getJSONObject("photo").getInt("owner_id")+"_"+k.getJSONObject("photo").getInt("id") + "_" + k.getJSONObject("photo").getString("access_key"));
                String att = "photo" + res.getJSONArray("response").getJSONObject(0).getInt("owner_id") + "_" + res.getJSONArray("response").getJSONObject(0).getInt("id") + "_" + res.getJSONArray("response").getJSONObject(0).getString("access_key");
                vk.messages().send(groupActor)
                        .randomId(ThreadLocalRandom.current().nextInt())
                        .userId(userId)
                        .peerId(groupActor.getId())
                        .attachment(att)
                        .message("Картинки переотправлены!")
                        .execute();
            }
            } catch (IOException e) {
                //couldn't get image, sorry -___-
                return;
            } catch (ApiException e) {
                e.printStackTrace();
            } catch (ClientException e) {
                e.printStackTrace();
            }
        }
    }
}
