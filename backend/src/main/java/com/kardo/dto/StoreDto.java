package com.kardo.dto;

/**
 * Tienda de la empresa, para elegir destino al dar de alta a alguien.
 *
 * @param users cuantas personas trabajan ya en ella
 */
public record StoreDto(long storeId, String code, String name, String address, int users) {
}
