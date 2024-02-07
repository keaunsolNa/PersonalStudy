package com.greedy.section04.array_sort;

public class Application5 {

    public static void main(String[] args) {

        /* 삽입정렬(insert sort) */
        /* 배열의 모든 요소를 앞에서부터 차례대로 이미 정렬된 배열과 비교하여 자신의 위치에 삽입하여 정렬
         * 배열의 두번째 데이터부터 연산을 시작한다.
         * 버블정렬의 비교횟수가 많은 단점을 개선하기 위해 고안된 정렬 방식이다.
         * */
        
        /* 초기 배열 선언 및 초기화 */
        int[] iarr = {2,5,4,6,1,3};
        
        int temp;
        int j = 0;
        
        for(int i = 1; i < iarr.length; i++) {
            
            temp = iarr[i];
            
            for(j = i - 1; j >= 0 && temp < iarr[j]; j--) {
                iarr[j+1] = iarr[j];
            }
            
            iarr[j+1] = temp;
            
        }
        
        /* 값 출력용 반복문 */
        for(int i = 0; i < iarr.length; i++){
            System.out.print(iarr[i] + " ");
        }

    }

}