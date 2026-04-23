# ✅ 2D CNN (Frame-level Violence vs Non-Violence) using MobileNetV2
# Works when your folders are like:
# train/
#   violent/
#   non_violent/
# val/
#   violent/
#   non_violent/
# test/
#   violent/
#   non_violent/

import os
import numpy as np
import tensorflow as tf
from tensorflow.keras.models import Model
from tensorflow.keras.layers import Dense, Dropout, GlobalAveragePooling2D
from tensorflow.keras.preprocessing.image import ImageDataGenerator
from tensorflow.keras.callbacks import EarlyStopping, ModelCheckpoint, ReduceLROnPlateau

# -------------------------
# Paths (Kaggle)
# -------------------------
train_dir = "/kaggle/input/datasets/jdkanna/2dcnnmajorprodataset/2D_CNN_DS/train"
val_dir   = "/kaggle/input/datasets/jdkanna/2dcnnmajorprodataset/2D_CNN_DS/val"
test_dir  = "/kaggle/input/datasets/jdkanna/2dcnnmajorprodataset/2D_CNN_DS/test"

assert os.path.exists(train_dir), f"Train path not found: {train_dir}"
assert os.path.exists(val_dir),   f"Val path not found: {val_dir}"
assert os.path.exists(test_dir),  f"Test path not found: {test_dir}"

# -------------------------
# Parameters
# -------------------------
IMG_SIZE   = (224, 224)
BATCH_SIZE = 32
EPOCHS_1   = 10   # frozen training
EPOCHS_2   = 10   # fine-tuning

# -------------------------
# IMPORTANT FIX:
# MobileNetV2 expects preprocess_input (not only rescale)
# -------------------------
preprocess = tf.keras.applications.mobilenet_v2.preprocess_input

train_datagen = ImageDataGenerator(
    preprocessing_function=preprocess,
    rotation_range=15,
    zoom_range=0.15,
    width_shift_range=0.10,
    height_shift_range=0.10,
    horizontal_flip=True
)

val_test_datagen = ImageDataGenerator(preprocessing_function=preprocess)

# -------------------------
# Generators (Binary: violent vs non_violent)
# -------------------------
train_gen = train_datagen.flow_from_directory(
    train_dir,
    target_size=IMG_SIZE,
    batch_size=BATCH_SIZE,
    class_mode="binary",
    shuffle=True
)

val_gen = val_test_datagen.flow_from_directory(
    val_dir,
    target_size=IMG_SIZE,
    batch_size=BATCH_SIZE,
    class_mode="binary",
    shuffle=False
)

test_gen = val_test_datagen.flow_from_directory(
    test_dir,
    target_size=IMG_SIZE,
    batch_size=BATCH_SIZE,
    class_mode="binary",
    shuffle=False
)

print("\n✅ Class Mapping (MUST be 2 classes):", train_gen.class_indices)
if len(train_gen.class_indices) != 2:
    raise ValueError(
        "Your dataset must have exactly 2 folders inside train/val/test.\n"
        "Example: train/violent and train/non_violent"
    )

# -------------------------
# Class Weights (handles imbalance)
# -------------------------
y_train = train_gen.classes
neg = np.sum(y_train == 0)
pos = np.sum(y_train == 1)
total = neg + pos
class_weight = {
    0: total / (2.0 * neg) if neg > 0 else 1.0,
    1: total / (2.0 * pos) if pos > 0 else 1.0
}
print("✅ class_weight:", class_weight)

# -------------------------
# Base Model
# -------------------------
base_model = tf.keras.applications.MobileNetV2(
    input_shape=(224, 224, 3),
    include_top=False,
    weights="imagenet"
)
base_model.trainable = False  # Stage-1 freeze

# -------------------------
# Head (Binary classifier)
# -------------------------
x = base_model.output
x = GlobalAveragePooling2D()(x)
x = Dense(256, activation="relu")(x)
x = Dropout(0.5)(x)
output = Dense(1, activation="sigmoid")(x)

model = Model(inputs=base_model.input, outputs=output)
model.compile(
    optimizer=tf.keras.optimizers.Adam(learning_rate=1e-4),
    loss="binary_crossentropy",
    metrics=[
        "accuracy",
        tf.keras.metrics.Precision(name="precision"),
        tf.keras.metrics.Recall(name="recall"),
        tf.keras.metrics.AUC(name="auc")
    ]
)
model.summary()

# -------------------------
# Callbacks
# -------------------------
callbacks = [
    EarlyStopping(monitor="val_auc", patience=4, mode="max", restore_best_weights=True),
    ReduceLROnPlateau(monitor="val_loss", factor=0.5, patience=2, min_lr=1e-6, verbose=1),
    ModelCheckpoint("best_violence_model.keras", monitor="val_auc", mode="max", save_best_only=True)
]

# -------------------------
# Train Stage 1 (Frozen)
# -------------------------
history1 = model.fit(
    train_gen,
    validation_data=val_gen,
    epochs=EPOCHS_1,
    class_weight=class_weight,
    callbacks=callbacks
)

# -------------------------
# Train Stage 2 (Fine-tune last layers)
# -------------------------
base_model.trainable = True

# Unfreeze only last N layers for stable fine-tuning
N = 40
for layer in base_model.layers[:-N]:
    layer.trainable = False

model.compile(
    optimizer=tf.keras.optimizers.Adam(learning_rate=1e-5),
    loss="binary_crossentropy",
    metrics=[
        "accuracy",
        tf.keras.metrics.Precision(name="precision"),
        tf.keras.metrics.Recall(name="recall"),
        tf.keras.metrics.AUC(name="auc")
    ]
)

history2 = model.fit(
    train_gen,
    validation_data=val_gen,
    epochs=EPOCHS_2,
    class_weight=class_weight,
    callbacks=callbacks
)

# -------------------------
# Evaluate
# -------------------------
test_metrics = model.evaluate(test_gen, verbose=1)
print("\n✅ Test Metrics:")
for name, val in zip(model.metrics_names, test_metrics):
    print(f"{name}: {val:.4f}")

# -------------------------
# Save Final Model
# -------------------------
model.save("violence_mobilenet_model.keras")
print("\n✅ Saved Keras model: violence_mobilenet_model.keras")

# -------------------------
# Convert BEST model to TFLite (recommended)
# -------------------------
best_model = tf.keras.models.load_model("best_violence_model.keras")

converter = tf.lite.TFLiteConverter.from_keras_model(best_model)
converter.optimizations = [tf.lite.Optimize.DEFAULT]

tflite_model = converter.convert()
with open("violence_mobilenet_model.tflite", "wb") as f:
    f.write(tflite_model)

print("✅ TFLite model saved: violence_mobilenet_model.tflite")