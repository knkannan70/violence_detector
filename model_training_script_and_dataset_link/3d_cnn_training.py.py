import os
os.environ['TF_CPP_MIN_LOG_LEVEL'] = '2'

import tensorflow as tf
import numpy as np
import cv2
import matplotlib.pyplot as plt
import seaborn as sns

from tensorflow.keras.layers import *
from tensorflow.keras.models import Model
from tensorflow.keras.applications import MobileNetV2
from tensorflow.keras.callbacks import EarlyStopping, ReduceLROnPlateau, ModelCheckpoint
from tensorflow.keras.utils import Sequence

from sklearn.metrics import classification_report, confusion_matrix, roc_curve, auc


# ==========================
# PARAMETERS
# ==========================

NUM_FRAMES = 16
FRAME_SIZE = 224
BATCH_SIZE = 8
EPOCHS = 30

train_dir = r"D:\3D_CNN_DS\violence_detection\dataset_3d\train"
val_dir = r"D:\3D_CNN_DS\violence_detection\dataset_3d\val"
test_dir = r"D:\3D_CNN_DS\violence_detection\dataset_3d\test"

classes = ["nonviolent", "violent"]


# ==========================
# FRAME PREPROCESSING
# ==========================

def preprocess_frame(frame):

    frame = cv2.cvtColor(frame, cv2.COLOR_BGR2RGB)

    frame = cv2.resize(frame, (FRAME_SIZE, FRAME_SIZE))

    frame = frame.astype(np.float32) / 255.0

    return frame


# ==========================
# VIDEO LOADER
# ==========================

def load_video(video_path):

    cap = cv2.VideoCapture(video_path)

    frames = []

    while len(frames) < NUM_FRAMES:

        ret, frame = cap.read()

        if not ret:
            break

        frame = preprocess_frame(frame)

        frames.append(frame)

    cap.release()

    if len(frames) == 0:
        return np.zeros((NUM_FRAMES, FRAME_SIZE, FRAME_SIZE, 3), dtype=np.float32)

    while len(frames) < NUM_FRAMES:
        frames.append(frames[-1])

    return np.array(frames, dtype=np.float32)


# ==========================
# DATA GENERATOR
# ==========================

class VideoGenerator(Sequence):

    def __init__(self, folder, batch_size=BATCH_SIZE):

        self.video_paths = []
        self.labels = []

        for label, cls in enumerate(classes):

            cls_path = os.path.join(folder, cls)

            for vid in os.listdir(cls_path):

                self.video_paths.append(os.path.join(cls_path, vid))

                self.labels.append(label)

        self.batch_size = batch_size


    def __len__(self):

        return int(np.ceil(len(self.video_paths) / self.batch_size))


    def __getitem__(self, idx):

        batch_paths = self.video_paths[idx*self.batch_size:(idx+1)*self.batch_size]

        batch_labels = self.labels[idx*self.batch_size:(idx+1)*self.batch_size]

        X = []

        for path in batch_paths:

            frames = load_video(path)

            X.append(frames)

        return np.array(X), np.array(batch_labels)



# ==========================
# CREATE GENERATORS
# ==========================

train_gen = VideoGenerator(train_dir)
val_gen = VideoGenerator(val_dir)
test_gen = VideoGenerator(test_dir)

print("Training videos:", len(train_gen.video_paths))
print("Validation videos:", len(val_gen.video_paths))
print("Test videos:", len(test_gen.video_paths))


# ==========================
# BUILD MODEL
# ==========================

def build_model():

    input_layer = Input(shape=(NUM_FRAMES, FRAME_SIZE, FRAME_SIZE, 3))

    base_model = MobileNetV2(
        weights="imagenet",
        include_top=False,
        input_shape=(FRAME_SIZE, FRAME_SIZE, 3)
    )

    base_model.trainable = False

    x = TimeDistributed(base_model)(input_layer)

    x = TimeDistributed(GlobalAveragePooling2D())(x)

    x = LSTM(128)(x)

    x = Dense(128, activation="relu")(x)

    x = Dropout(0.5)(x)

    output = Dense(1, activation="sigmoid")(x)

    model = Model(inputs=input_layer, outputs=output)

    return model


# ==========================
# COMPILE MODEL
# ==========================

model = build_model()

model.compile(
    optimizer=tf.keras.optimizers.Adam(1e-4),
    loss="binary_crossentropy",
    metrics=["accuracy"]
)

model.summary()


# ==========================
# CALLBACKS
# ==========================

early_stop = EarlyStopping(
    monitor="val_loss",
    patience=5,
    restore_best_weights=True
)

reduce_lr = ReduceLROnPlateau(
    monitor="val_loss",
    factor=0.3,
    patience=3
)

checkpoint = ModelCheckpoint(
    "best_violence_model.h5",
    monitor="val_accuracy",
    save_best_only=True
)


# ==========================
# TRAIN MODEL
# ==========================

history = model.fit(

    train_gen,

    validation_data=val_gen,

    epochs=EPOCHS,

    callbacks=[early_stop, reduce_lr, checkpoint]

)


# ==========================
# TEST MODEL
# ==========================

test_loss, test_acc = model.evaluate(test_gen)

print("Test Accuracy:", test_acc)


# ==========================
# PREDICTIONS
# ==========================

y_true = []
y_pred_prob = []

for X_batch, y_batch in test_gen:

    preds = model.predict(X_batch)

    y_pred_prob.extend(preds)

    y_true.extend(y_batch)


y_pred_prob = np.array(y_pred_prob)

y_pred = (y_pred_prob > 0.5).astype(int)


# ==========================
# CLASSIFICATION REPORT
# ==========================

report = classification_report(
    y_true,
    y_pred,
    target_names=["Non-Violent", "Violent"]
)

print(report)

with open("classification_report.txt","w") as f:
    f.write(report)


# ==========================
# CONFUSION MATRIX
# ==========================

cm = confusion_matrix(y_true, y_pred)

plt.figure(figsize=(6,5))

sns.heatmap(
    cm,
    annot=True,
    fmt="d",
    cmap="Blues",
    xticklabels=["Non-Violent","Violent"],
    yticklabels=["Non-Violent","Violent"]
)

plt.xlabel("Predicted")
plt.ylabel("Actual")
plt.title("Confusion Matrix")

plt.savefig("confusion_matrix.png")

plt.close()


# ==========================
# ROC AUC
# ==========================

fpr, tpr, _ = roc_curve(y_true, y_pred_prob)

roc_auc = auc(fpr, tpr)

plt.figure()

plt.plot(fpr, tpr, label="AUC = %0.4f" % roc_auc)

plt.plot([0,1],[0,1],'r--')

plt.xlabel("False Positive Rate")
plt.ylabel("True Positive Rate")
plt.title("ROC Curve")

plt.legend(loc="lower right")

plt.savefig("roc_auc_curve.png")

plt.close()

print("AUC Score:", roc_auc)


# ==========================
# SAVE MODEL
# ==========================

model.save("violence_model_hybrid.h5")

model.save("violence_saved_model")


# ==========================
# TFLITE CONVERSION
# ==========================

converter = tf.lite.TFLiteConverter.from_saved_model("violence_saved_model")

converter.optimizations = [tf.lite.Optimize.DEFAULT]

converter.target_spec.supported_types = [tf.float16]

tflite_model = converter.convert()

with open("violence_model_fp16.tflite","wb") as f:
    f.write(tflite_model)


print("Training completed successfully")